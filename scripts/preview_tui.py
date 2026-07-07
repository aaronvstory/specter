import sys, os, json, tempfile
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from specter import tui, profile as P
d = tempfile.mkdtemp()
# seed an active profile + a couple saved
store = P.UsedStore(os.path.join(d,"used_ids.json"))
p = P.generate_unique(store, seed=7); store.save()
json.dump(p, open(os.path.join(d,"profile.json"),"w"))
json.dump({"alice":P.generate_unique(store,seed=1),"bob_backup":P.generate_unique(store,seed=2)}, open(os.path.join(d,"profiles.json"),"w"))
dash = tui.Dashboard(d)
dash.console.print(dash.render())
