#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
复现「教务助手」APK 的网络请求形状，验证接口契约 —— 用于在没有真机时验证 App 的接口层。

严格对齐 JwApi.kt：
  · UA / Accept-Language 与 App 相同
  · 登录 POST 不带 X-Requested-With（App 只在课表接口带）
  · 课表 POST 带 X-Requested-With，参数 = 课表页隐藏字段 + xnm/xqm/gnmkdm/page=1/rows=5000（无 kzlx）
  · 学年学期候选顺序：校历 → 按当前月份推算的学年 → 兜底

用法: HAUE_PW='<密码>' python verify_app_requests.py [学号]
"""
import os, re, sys, json, time, datetime, argparse, base64, secrets
import http.cookiejar, urllib.request, urllib.parse


def rsa_encrypt_pkcs1v15(text, modulus_b64, exponent_b64):
    """PKCS#1 v1.5 加密，纯标准库实现（与 App 里 JwApi 的做法一致）。"""
    n = int.from_bytes(base64.b64decode(modulus_b64), "big")
    e = int.from_bytes(base64.b64decode(exponent_b64), "big")
    k = (n.bit_length() + 7) // 8
    msg = text.encode("utf-8")
    pad_len = k - len(msg) - 3
    if pad_len < 8:
        raise ValueError("明文超出 RSA 块长度")
    ps = bytes(b for b in secrets.token_bytes(pad_len * 2) if b != 0)[:pad_len]
    while len(ps) < pad_len:
        ps += bytes(b for b in secrets.token_bytes(pad_len) if b != 0)[: pad_len - len(ps)]
    em = b"\x00\x02" + ps + b"\x00" + msg
    return base64.b64encode(pow(int.from_bytes(em, "big"), e, n).to_bytes(k, "big")).decode()

HOST = "124.system.haue.edu.cn"
BASE = "http://" + HOST
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"


def opener():
    cj = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    op.addheaders = [("User-Agent", UA), ("Accept-Language", "zh-CN,zh;q=0.9")]
    return op, cj


def timed(op, req, tag):
    t0 = time.perf_counter()
    r = op.open(req, timeout=40)
    b = r.read()
    return b, (time.perf_counter() - t0) * 1000


def parse_inputs(html):
    out = {}
    for tag in re.findall(r"<input\b[^>]*>", html, re.I):
        m = re.search(r'name="([^"]*)"', tag)
        if not m:
            continue
        v = re.search(r'value="([^"]*)"', tag)
        out.setdefault(m.group(1), v.group(1) if v else "")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("yhm", nargs="?", default="")
    ap.add_argument("--pw-env", default="HAUE_PW")
    a = ap.parse_args()
    pw = os.environ.get(a.pw_env, "")
    if not pw:
        sys.exit(f"环境变量 {a.pw_env} 为空")

    op, cj = opener()
    ok = True

    # ── 1. 登录页 ──
    page, ms = timed(op, BASE + "/jwglxt/xtgl/login_slogin.html", "登录页")
    html = page.decode("utf-8", "replace")
    csrf = re.search(r'name="csrftoken"\s+value="([^"]+)"', html)
    print(f"[1] 登录页 {ms:.0f}ms  {len(page)/1024:.1f}KB  csrftoken={'有' if csrf else '无'}")
    ok &= bool(csrf)
    hidden = parse_inputs(html)

    # ── 2. 公钥 ──
    pkBody, ms = timed(op, BASE + f"/jwglxt/xtgl/login_getPublicKey.html?time={int(time.time()*1000)}", "公钥")
    pk = json.loads(pkBody.decode())
    print(f"[2] 公钥接口 {ms:.0f}ms  modulus={len(pk['modulus'])}B(b64) exponent={pk['exponent']}")
    ok &= "modulus" in pk

    # ── 3. 登录 POST（App 不带 X-Requested-With）──
    form = {"yhm": a.yhm, "mm": rsa_encrypt_pkcs1v15(pw, pk["modulus"], pk["exponent"]),
            "csrftoken": csrf.group(1), "language": "zh_CN", "ydType": ""}
    for k in ("yzcskz", "mmsfjm", "mmsrddcshkzfs", "dlsbsdsj", "dlmmsfknt", "xxdm",
              "userPolicyOn", "dxsyrz", "sfxswechatlogin", "sfzywqh", "scene_id"):
        form[k] = hidden.get(k, "")
    body, ms = timed(op, urllib.request.Request(
        BASE + "/jwglxt/xtgl/login_slogin.html",
        data=urllib.parse.urlencode(form).encode(),
        headers={"Referer": BASE + "/jwglxt/xtgl/login_slogin.html",
                 "Content-Type": "application/x-www-form-urlencoded"}), "登录")
    txt = body.decode("utf-8", "replace")
    if "用户名或密码不正确" in txt:
        print("[3] 登录失败：用户名或密码不正确"); sys.exit(1)
    print(f"[3] 登录 POST {ms:.0f}ms  返回 {len(body)/1024:.1f}KB")

    # ── 4. 校验会话 ──
    home, ms = timed(op, BASE + "/jwglxt/xtgl/index_initMenu.html?jsdm=xs", "首页")
    homeTxt = home.decode("utf-8", "replace")
    logged = "login_slogin" not in homeTxt
    print(f"[4] 首页 {ms:.0f}ms  会话有效={logged}  含学号={a.yhm in homeTxt}")
    ok &= logged

    # ── 5. 校历 ──
    cal = None
    try:
        a5, ms = timed(op, BASE + "/jwglxt/xtgl/index_cxAreaFive.html?gnmkdm=index", "校历")
        m = re.search(r"(\d{4})-(\d{4})学年\s*(\d)\s*学期\s*[（(]\s*(\d{4}-\d{2}-\d{2})\s*至\s*(\d{4}-\d{2}-\d{2})",
                      re.sub(r"<[^>]+>", " ", a5.decode("utf-8", "replace")))
        if m:
            cal = {"xnm": m.group(1), "xqm": {"1": "3", "2": "12"}.get(m.group(3), "16"),
                   "start": m.group(4), "end": m.group(5)}
            print(f"[5] 校历 {ms:.0f}ms  {m.group(1)}-{m.group(2)}学年 第{m.group(3)}学期 {cal['start']} ~ {cal['end']}")
    except Exception as e:
        print(f"[5] 校历 取不到（忽略）: {e}")

    # ── 6. 课表 POST（App 形状：隐藏字段 + xnm/xqm/gnmkdm/page/rows + X-Requested-With）──
    idxUrl = BASE + "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151"
    idx, ms = timed(op, idxUrl, "课表页")
    hidx = parse_inputs(idx.decode("utf-8", "replace"))
    print(f"[6] 课表页 {ms:.0f}ms  表单字段 {len(hidx)} 个")

    now = datetime.date.today()
    y = now.year if now.month >= 8 else now.year - 1
    cands = []
    if cal:
        cands.append((cal["xnm"], cal["xqm"]))
    cands += [(str(y), "3"), (str(y), "12"), (str(y - 1), "12"), (str(y - 1), "3")]
    cands = list(dict.fromkeys(cands))

    got = None
    for xnm, xqm in cands:
        d = dict(hidx, xnm=xnm, xqm=xqm, gnmkdm="N2151", page="1", rows="5000")
        b, ms = timed(op, urllib.request.Request(
            BASE + "/jwglxt/kbcx/xskbcx_cxXsgrkb.html",
            data=urllib.parse.urlencode(d).encode(),
            headers={"Referer": idxUrl, "X-Requested-With": "XMLHttpRequest",
                     "Content-Type": "application/x-www-form-urlencoded"}), "课表")
        try:
            j = json.loads(b.decode("utf-8", "replace"))
        except Exception:
            print(f"    {xnm}/{xqm}: {ms:.0f}ms  非 JSON"); continue
        n = len(j.get("kbList") or []) + len(j.get("sjkList") or [])
        print(f"    {xnm}/{xqm}: {ms:.0f}ms  课程 {n} 条  {len(b)/1024:.1f}KB")
        if n > 0:
            got = (j, xnm, xqm)
            break

    if not got:
        print("[7] ✗ 所有候选学年学期都没取到课表"); sys.exit(1)

    j, xnm, xqm = got
    xs = j["xsxx"]
    print(f"[7] ✓ 取到课表  学期 {xs['XNM']}/{xs['XQM']}（{xs['XNMC']}）  学号 {xs['XH']}  {xs['XM']}  {xs['BJMC']}")

    # ── 8. 用 App 的解析规则汇总 ──
    def parse_weeks(zcd):
        weeks, odd, even = set(), "单" in zcd, "双" in zcd
        for part in re.split(r"[,，;；]", zcd.replace("周", "")):
            part = re.sub(r"[()（）]", "", part).strip()
            if not part:
                continue
            m = re.match(r"^(\d+)\s*-\s*(\d+)$", part)
            if m:
                weeks |= set(range(int(m.group(1)), int(m.group(2)) + 1))
            elif re.match(r"^\d+$", part):
                weeks.add(int(part))
        if odd:
            weeks = {w for w in weeks if w % 2}
        if even:
            weeks = {w for w in weeks if w % 2 == 0}
        return weeks

    rows = [(o["kcmc"], o["xm"], o["cdmc"], int(o["xqj"]), o["jcs"], o["zcd"]) for o in j["kbList"]]
    wk = set()
    for r in rows:
        wk |= parse_weeks(r[5])
    for o in (j.get("sjkList") or []):
        wk |= parse_weeks(o.get("qsjsz") or o.get("sjkcgs", ""))
    start = datetime.date.fromisoformat(cal["start"]) if cal else None
    maxw = max(max(wk), (datetime.date.fromisoformat(cal["end"]) - start).days // 7 + 1) if cal else max(wk)
    print(f"[8] 解析：理论课 {len(rows)} 条 + 集中实践 {len(j.get('sjkList') or [])} 条，覆盖第 1~{maxw} 周")
    print("    前 5 条:")
    for r in rows[:5]:
        print(f"      {r[0]} | {r[1]} | {r[2]} | 周{'一二三四五六日'[r[3]-1]} {r[4]}节 | {r[5]}")

    op.open(BASE + "/jwglxt/xtgl/login_logout.html", timeout=15).read()
    print(f"\n结果: {'✅ App 的请求形状与接口契约全部验证通过' if ok else '⚠ 有项目未通过，见上'}")
    print("[*] 已退出登录")


if __name__ == "__main__":
    main()
