import json, sys
src, dst = sys.argv[1], sys.argv[2]
d = json.load(open(src))
d['trace'] = '1'
core = "processor\t: {n}\nBogoMIPS\t: 26.00\nCPU implementer\t: 0x41\nCPU part\t: 0xd05\n\n"
cpuinfo = "".join(core.format(n=i) for i in range(8))
# store with \n and \t escaped as two-char sequences (the C decoder turns \\n->\n, \\t->\t)
d['proc_cpuinfo'] = cpuinfo.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
open(dst, "w", newline="").write(json.dumps(d))
print("ok, keys:", len(d))
