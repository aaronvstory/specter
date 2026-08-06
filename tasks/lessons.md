# Lessons

## open('wb').write(bytes + str) truncates on the failed concat
- **Mistake:** Twice this session I wrote `open(p,'wb').write(b[:i]+sec+b[i:])` where the insert was a
  `str` and `b` was `bytes`. `open(p,'wb')` TRUNCATES the file immediately, THEN the `bytes+str` concat
  raises TypeError — so the write never runs and the file is left EMPTY. Emptied README.md and
  session-log.md (the latter got committed empty before I caught it).
- **Rule:** Build the full byte string FIRST, then open+write: `new = b[:i]+sec_bytes+b[i:]` on its own
  line, `with open(p,'wb') as f: f.write(new)`. Make every insert a `bytes` literal/`.encode('utf-8')`.
  Never put a concat that can raise inside the `open('wb').write(...)` call.
- **Context:** every byte-level EOL-preserving edit on Windows (the CRLF-safe pattern).

- **ROBUST FIX (use this):** for an APPEND, open with 'ab' not 'wb' - 'ab' never truncates, so a failed
  write leaves the file intact. And make the payload real bytes via .encode('utf-8'), never a str with
  backslash-x escapes. Hit this 3x in one session before switching to 'ab'.
