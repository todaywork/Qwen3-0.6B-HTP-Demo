# -*- coding: utf-8 -*-
"""解析 aarch64 ELF .so 的动态符号表，列出未定义（外部依赖）符号"""
import struct, sys

def dynsym_names(path):
    with open(path, "rb") as f:
        data = f.read()
    assert data[:4] == b"\x7fELF", "not ELF"
    is64 = data[4] == 2
    assert is64, "expect 64-bit"
    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", data, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", data, 0x3C)[0]
    secs = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name, stype, flags, addr, offset, size, link, info, align, entsize = struct.unpack_from("<IIQQQQIIQQ", data, off)
        secs.append((name, stype, offset, size, link, entsize))
    # 找 section 名表
    shstrndx = struct.unpack_from("<H", data, 0x3E)[0]
    shstr_off = secs[shstrndx][2]
    def secname(n):
        end = data.index(b"\x00", shstr_off + n)
        return data[shstr_off + n:end].decode()
    syms = []
    for s in secs:
        if secname(s[0]) == ".dynsym":
            strtab = secs[s[4]]
            stroff = strtab[2]
            count = s[3] // s[5]
            for i in range(count):
                off = s[2] + i * s[5]
                st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from("<IBBHQQ", data, off)
                end = data.index(b"\x00", stroff + st_name)
                nm = data[stroff + st_name:end].decode()
                bind, typ = st_info >> 4, st_info & 0xF
                # st_shndx == 0 → 未定义（外部导入）
                syms.append((nm, st_shndx == 0, bind, typ))
    return syms

if __name__ == "__main__":
    path = sys.argv[1]
    syms = dynsym_names(path)
    undef = sorted({n for n, u, b, t in syms if u and n})
    print(f"== {path}")
    print(f"外部依赖符号 {len(undef)} 个，关键映射类函数:")
    keys = ["fastrpc_mmap", "rpcmem_alloc", "rpcmem_free", "rpcmem_to_fd",
            "remote_register_dma_handle", "remote_handle_open", "remote_handle_invoke"]
    for k in keys:
        print(f"  {'✓' if k in undef else '✗'} {k}")
    others = [s for s in undef if "rpcmem" in s or "fastrpc" in s or "remote_" in s]
    print("  --- 全部 rpcmem/fastrpc/remote_* 依赖 ---")
    for s in others:
        print(f"    {s}")
    # 错误字符串佐证
    with open(path, "rb") as f:
        data = f.read()
    for probe in (b"fastrpc memory map", b"stubLoadMultiPdFuncs"):
        print(f"  字符串 {probe.decode()!r}: {'命中' if probe in data else '未命中'}")
