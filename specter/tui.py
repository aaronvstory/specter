"""
tui.py — specter dashboard (rich-based, themed, light/dark safe).

Keys:  n new · p push · s save · r reuse · d details · v verify · t stats · q quit
Shows: active identity (key fields), saved-profile vault, issued-count ledger, device status.
"""
import json
import os

from rich.console import Console, Group
from rich.panel import Panel
from rich.table import Table
from rich.text import Text
from rich import box

from .theme import THEME, chip
from . import profile as P
from . import device as D
from .identifiers import UNIQUE_KEYS

# Fleet safety: the dev tool defaults to the DevInfo test app, NEVER a real fleet app
# (Dasher/system). Push elsewhere only by explicit --pkg.
DEFAULT_PKG = "com.liuzh.deviceinfo"


def _load(path, default):
    try:
        return json.load(open(path))
    except Exception:
        return default


# Single source of truth for the main menu: (key, label, action-method). The key is both the
# numbered/lettered shortcut and the questionary/rich value, so muscle memory works in every
# renderer. Matches the CustomerDaisy/persona-swapper questionnaire style (arrow-select with a
# rich fallback) instead of a raw single-keypress loop.
MENU = [
    ("n", "New identity", "act_new"),
    ("p", "Push to device (adb)", "act_push"),
    ("s", "Save to vault", "act_save"),
    ("r", "Reuse a saved identity", "act_reuse"),
    ("d", "Details (all fields)", "act_details"),
    ("t", "Stats (issued ledger)", "act_stats"),
    ("v", "Verify on-device", "act_verify"),
    ("q", "Quit", None),
]


def _menu_choice(console):
    """Ask the user to pick a menu item — questionary arrow-select, rich Prompt fallback."""
    try:
        import questionary
        choices = [questionary.Choice(f"{k.upper()}  {label}", value=k) for k, label, _ in MENU]
        ans = questionary.select("What next?", choices=choices, qmark="👻", instruction=" ").ask()
        return ans or "q"  # ask() returns None on Ctrl-C
    except Exception:
        # rich fallback: numbered prompt (no questionary or non-interactive terminal)
        from rich.prompt import Prompt
        keys = [k for k, _, _ in MENU]
        console.print("  " + "   ".join(f"[key]{k}[/] {label}" for k, label, _ in MENU))
        return Prompt.ask("[key]choose[/]", choices=keys, default="n", show_choices=False)


class Dashboard:
    def __init__(self, root):
        self.root = root
        self.USED = os.path.join(root, "used_ids.json")
        self.VAULT = os.path.join(root, "profiles.json")
        self.ACTIVE = os.path.join(root, "profile.json")
        self.pkg = DEFAULT_PKG
        self.msg = "ready"
        self.msg_style = "muted"
        self.console = Console(theme=THEME)

    # ---- rendering ----
    def _active_panel(self):
        p = _load(self.ACTIVE, {})
        t = Table.grid(padding=(0, 2))
        t.add_column(style="key", justify="right")
        t.add_column(style="val")
        if not p:
            t.add_row("", Text("no active identity — press 'n'", style="muted"))
        else:
            show = [
                ("device", f"{p.get('build_manufacturer','')} {p.get('build_model','')}"),
                ("android_id", p.get("android_id", "")),
                ("gsf_id", p.get("gsf_id", "")),
                ("imei1", p.get("imei1", "")),
                ("serial", p.get("serial", "")),
                ("advertising_id", p.get("advertising_id", "")),
                ("wifi_mac", p.get("wifi_mac", "")),
                ("media_drm_id", p.get("media_drm_id", "")),
                ("SIM", f"{p.get('sim_operator_name','')} ({p.get('sim_operator_mccmnc','')})"),
                ("fingerprint", Text(p.get("build_fingerprint", ""), style="device", overflow="ellipsis")),
            ]
            for k, v in show:
                t.add_row(k, v if isinstance(v, Text) else str(v))
        return Panel(t, title="[brand]ACTIVE IDENTITY[/]", border_style="magenta", box=box.ROUNDED)

    def _vault_panel(self):
        vault = _load(self.VAULT, {})
        t = Table(box=box.SIMPLE, show_edge=False, pad_edge=False)
        t.add_column("name", style="unique")
        t.add_column("device", style="muted")
        t.add_column("gsf", style="muted")
        for n, p in list(vault.items())[:8]:
            t.add_row(n, f"{p.get('build_model','')}", p.get("gsf_id", ""))
        if not vault:
            t.add_row("(empty)", "", "")
        return Panel(t, title="[brand]SAVED[/]", border_style="cyan", box=box.ROUNDED)

    def _footer(self):
        used = _load(self.USED, {})
        count = len(used.get("gsf_id", []))
        left = Text.assemble(("issued (never reused): ", "muted"), (str(count), "fresh"),
                             ("   target: ", "muted"), (self.pkg, "device"))
        # msg may contain rich markup (chips) — render it, don't print the tags literally
        status = Text.from_markup(self.msg) if "[" in self.msg else Text(self.msg, style=self.msg_style)
        return Group(left, status)

    def render(self):
        top = Table.grid(expand=True)
        top.add_column(ratio=3); top.add_column(ratio=2)
        top.add_row(self._active_panel(), self._vault_panel())
        header = Text("  👻 specter — device identity rotation", style="brand")
        return Group(header, top, self._footer())

    # ---- actions ----
    def act_new(self):
        store = P.UsedStore(self.USED)
        p = P.generate_unique(store)
        json.dump(p, open(self.ACTIVE, "w"), indent=2)
        self.msg = f"{chip('NEW','chip.new')} {p['build_model']}  gsf={p['gsf_id']}"
        self.msg_style = "good"

    def act_push(self):
        p = _load(self.ACTIVE, {})
        if not p:
            self.msg, self.msg_style = "no active identity", "warn"; return
        if not D.device_connected():
            self.msg, self.msg_style = f"{chip('NO DEVICE','chip.err')} adb not connected", "bad"; return
        try:
            D.push_profile(p, self.pkg); D.clear_app(self.pkg)
            self.msg = f"{chip('PUSHED','chip.push')} {self.pkg} cleared"; self.msg_style = "good"
        except D.AdbError as e:
            self.msg, self.msg_style = f"{chip('ERR','chip.err')} {e}", "bad"

    def act_save(self):
        name = self.console.input("[key]save as:[/] ").strip()
        if name:
            vault = _load(self.VAULT, {}); vault[name] = _load(self.ACTIVE, {})
            json.dump(vault, open(self.VAULT, "w"), indent=2)
            self.msg, self.msg_style = f"saved '{name}'", "good"

    def act_reuse(self):
        name = self.console.input("[key]reuse name:[/] ").strip()
        vault = _load(self.VAULT, {})
        if name in vault:
            json.dump(vault[name], open(self.ACTIVE, "w"), indent=2)
            self.msg, self.msg_style = f"loaded '{name}'", "good"
        else:
            self.msg, self.msg_style = f"'{name}' not found", "warn"

    def act_details(self):
        self.console.clear()
        self.console.print(Panel(json.dumps(_load(self.ACTIVE, {}), indent=2), title="active profile (all fields)"))
        self.console.input("[muted]press Enter[/]")

    def act_stats(self):
        used = _load(self.USED, {})
        from .identifiers import UNIQUE_KEYS
        from rich.table import Table as _T
        t = _T(box=None)
        t.add_column("identifier", style="key"); t.add_column("issued", style="fresh")
        for k in UNIQUE_KEYS:
            t.add_row(k, str(len(used.get(k, []))))
        self.console.clear()
        self.console.print(Panel(t, title="[brand]issued identities (never reused)[/]", border_style="cyan"))
        self.console.input("[muted]press Enter[/]")

    def act_verify(self):
        from . import verify
        self.console.clear()
        try:
            verify.run_questionnaire(console=self.console)
        except Exception as e:
            self.msg, self.msg_style = f"verify errored: {e}", "bad"
        self.console.input("\n[muted]press Enter to return to dashboard[/]")


def run(root):
    dash = Dashboard(root)
    con = dash.console
    actions = {k: fn for k, _, fn in MENU if fn}
    while True:
        con.clear()
        con.print(dash.render())
        con.print()
        try:
            choice = _menu_choice(con)
        except (KeyboardInterrupt, EOFError):
            break
        if choice == "q":
            break
        fn = actions.get(choice)
        if fn:
            try:
                getattr(dash, fn)()
            except Exception as e:
                dash.msg, dash.msg_style = f"{fn} errored: {e}", "bad"
    con.print("[muted]bye 👻[/]")
