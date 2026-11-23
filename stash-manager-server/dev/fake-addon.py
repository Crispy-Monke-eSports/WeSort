#!/usr/bin/env python3
"""Fake WeSort addon for testing the WeSort Manager server without Minecraft.

Standard library only. Run it isolated so nothing is imported from the current folder:

    python -I dev/fake-addon.py --url http://127.0.0.1:8768 --data <dataDir> push
    python -I dev/fake-addon.py --url ... --data ... push --shulker-colours --mc 26.3   # icon test index
    python -I dev/fake-addon.py --url ... --data ... heartbeat
    python -I dev/fake-addon.py --url ... --data ... --no-delivery --blocker "no delivery box" heartbeat
    python -I dev/fake-addon.py --url ... --data ... --naming true:30 heartbeat   # anvil set, 30 XP levels
    python -I dev/fake-addon.py --url ... --data ... --naming null serve          # a bot that can't name shulkers
    python -I dev/fake-addon.py --url ... --data ... --crafting true:true heartbeat   # crafting on, table set
    python -I dev/fake-addon.py --url ... --data ... claim
    python -I dev/fake-addon.py --url ... --data ... status o-0123456789ab in_progress --phase "gathering" --done 1 --total 3
    python -I dev/fake-addon.py --url ... --data ... serve        # heartbeats for 2 min; answers rescan requests
    python -I dev/fake-addon.py --url ... --data ... serve --rescan-blocked "sorter off"
    python -I dev/fake-addon.py --url ... --data ... delivery --used 20 --chests 3   # delivery storage contents
    python -I dev/fake-addon.py --url ... --data ... run          # claim and walk one order to delivered
    python -I dev/fake-addon.py --url ... --data ... run --fail   # ... or to failed

The API key comes from --key, $WESORT_MANAGER_KEY, or apiKey= in <dataDir>/config.properties.
"""

import argparse
import re
import copy
import hashlib
import hmac
import json
import os
import sys
import threading
import time
import urllib.parse
import urllib.error
import urllib.request

SERVER = "2b2t.org"
STASH = "dev"
PLAYER = "FakeBot"


def item(key, name, count, tab, tab_order, max_stack=64, details=None, enchanted=False, english=None):
    item_id = key.split("#", 1)[0]
    return {
        "key": key,
        "id": item_id,
        "name": name,
        "englishName": english or name,
        "count": count,
        "maxStack": max_stack,
        "details": details or [],
        "enchanted": enchanted,
        "tab": tab,
        "tabOrder": tab_order,
    }


def shulker_key(item_key, contents):
    canon = item_key + "|" + ",".join(f"{c['slot']}:{c['key']}:{c['count']}" for c in contents)
    return "s:" + hashlib.sha1(canon.encode()).hexdigest()[:10]


def build_index(tabs=True, mc="26.3", player=None, extra_diamonds=0, containers=None):
    diamonds_box = [{"slot": s, "key": "minecraft:diamond", "count": 64} for s in range(27)]
    kit = [
        {"slot": 0, "key": "minecraft:diamond_sword#3f2a9c1d", "count": 1},
        {"slot": 1, "key": "minecraft:golden_apple", "count": 64},
        {"slot": 13, "key": "minecraft:totem_of_undying", "count": 1},
        {"slot": 26, "key": "minecraft:ender_pearl", "count": 16},
    ]
    shulkers = [
        {"key": shulker_key("minecraft:red_shulker_box", diamonds_box), "id": "minecraft:red_shulker_box",
         "itemKey": "minecraft:red_shulker_box", "name": "Red Shulker Box", "count": 4, "contents": diamonds_box},
        {"key": shulker_key("minecraft:shulker_box#abcd1234", kit), "id": "minecraft:shulker_box",
         "itemKey": "minecraft:shulker_box#abcd1234", "name": "PvP Kit", "count": 1, "contents": kit},
    ]
    items = [
        item("minecraft:stone", "Stone", 3456, "building_blocks", 1),
        item("minecraft:oak_planks", "Oak Planks", 1200, "building_blocks", 40),
        item("minecraft:oak_stairs", "Oak Stairs", 300, "building_blocks", 41),
        item("minecraft:cyan_wool", "Cyan Wool", 640, "colored_blocks", 10),
        item("minecraft:red_shulker_box", "Red Shulker Box", 6, "colored_blocks", 300, max_stack=1),
        item("minecraft:shulker_box", "Shulker Box", 3, "colored_blocks", 290, max_stack=1),
        item("minecraft:shulker_box#abcd1234", "PvP Kit", 1, "colored_blocks", 290, max_stack=1, english="Shulker Box"),
        item("minecraft:grass_block", "Grass Block", 128, "natural_blocks", 1),
        item("minecraft:torch", "Torch", 900, "functional_blocks", 1),
        item("minecraft:chest", "Chest", 54, "functional_blocks", 60),
        item("minecraft:redstone", "Redstone Dust", 2000, "redstone_blocks", 1),
        item("minecraft:diamond_pickaxe", "Diamond Pickaxe", 3, "tools_and_utilities", 20, max_stack=1),
        item("minecraft:diamond_sword#3f2a9c1d", "Diamond Sword", 2, "combat", 5, max_stack=1,
             details=["Sharpness V", "Unbreaking III"], enchanted=True),
        item("minecraft:totem_of_undying", "Totem of Undying", 40, "combat", 80, max_stack=1),
        item("minecraft:golden_apple", "Golden Apple", 64 + 320, "food_and_drinks", 30),
        item("minecraft:ender_pearl", "Ender Pearl", 16 + 160, "ingredients", 5, max_stack=16),
        item("minecraft:diamond", "Diamond", 4 * 27 * 64 + 500 + extra_diamonds, "ingredients", 20),
        item("minecraft:pig_spawn_egg", "Pig Spawn Egg", 2, "spawn_eggs", 50),
        item("minecraft:knowledge_book", "<b>Knowledge</b> & \"Book\"", 1, "other", 1000000, max_stack=1),
    ]
    if not tabs:
        for i in items:
            i.pop("tab")
            i.pop("tabOrder")
    index = {
        "version": 1,
        "server": SERVER,
        "stash": STASH,
        "player": player or PLAYER,
        "mcVersion": mc,
        "dimension": "minecraft:overworld",
        "generatedAt": int(time.time() * 1000),
        "containers": containers or {"known": 42, "total": 45, "stale": 1},
        "items": items,
    }
    if tabs:
        index["shulkers"] = shulkers
    index["indexHash"] = hashlib.sha1(json.dumps(items, sort_keys=True).encode()).hexdigest()
    return index


COLOURS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
           "purple", "blue", "brown", "green", "red", "black"]


def add_colour_shulkers(index):
    """Every shulker colour (some only empty, some filled with one item, some mixed) plus the other
    block-entity items, for checking icons: push --shulker-colours."""
    items = index["items"]
    shulkers = index.setdefault("shulkers", [])
    have = {i["key"] for i in items}
    fills = {
        "white": [("minecraft:stone", 64)] * 27,
        "orange": [("minecraft:torch", 64)] * 10,
        "light_blue": [("minecraft:diamond", 64)] * 5 + [("minecraft:golden_apple", 64)] * 2,
        "yellow": [("minecraft:golden_apple", 64)] * 3 + [("minecraft:ender_pearl", 16)] * 9 + [("minecraft:redstone", 64)],
        "lime": [("minecraft:oak_planks", 64)] * 27,
        "gray": [("minecraft:redstone", 64)] * 12,
        "cyan": [("minecraft:totem_of_undying", 1)] * 27,
        "purple": [("minecraft:ender_pearl", 16)] * 27,
        "blue": [("minecraft:diamond", 64), ("minecraft:stone", 64)],
        "green": [("minecraft:grass_block", 64)] * 6,
        "black": [("minecraft:diamond_pickaxe", 1), ("minecraft:totem_of_undying", 1), ("minecraft:golden_apple", 64)],
    }
    for n, colour in enumerate(COLOURS):
        key = f"minecraft:{colour}_shulker_box"
        name = colour.replace("_", " ").title() + " Shulker Box"
        groups = []
        if colour in fills:
            contents = [{"slot": s, "key": k, "count": c} for s, (k, c) in enumerate(fills[colour])]
            groups.append({"key": shulker_key(key, contents), "id": key, "itemKey": key, "name": name,
                           "count": 1 + n % 3, "contents": contents})
        empty = 0 if colour in ("lime", "purple") else 2 + n
        total = empty + sum(g["count"] for g in groups)
        if key in have:
            for i in items:
                if i["key"] == key:
                    i["count"] = i["count"] + total
        else:
            items.append(item(key, name, total, "colored_blocks", 300 + n, max_stack=1))
        shulkers.extend(groups)
    for n, (key, name, tab) in enumerate([
            ("minecraft:trapped_chest", "Trapped Chest", "redstone_blocks"), ("minecraft:ender_chest", "Ender Chest", "functional_blocks"),
            ("minecraft:red_bed", "Red Bed", "colored_blocks"), ("minecraft:white_banner", "White Banner", "colored_blocks"),
            ("minecraft:black_banner", "Black Banner", "colored_blocks"), ("minecraft:skeleton_skull", "Skeleton Skull", "functional_blocks"),
            ("minecraft:player_head", "Player Head", "functional_blocks"), ("minecraft:dragon_head", "Dragon Head", "functional_blocks"),
            ("minecraft:shield", "Shield", "combat"), ("minecraft:conduit", "Conduit", "functional_blocks"),
            ("minecraft:decorated_pot", "Decorated Pot", "functional_blocks")]):
        if key not in have:
            items.append(item(key, name, 1 + n, tab, 400 + n, max_stack=1 if "banner" not in key else 16))
    index["indexHash"] = hashlib.sha1(json.dumps(items, sort_keys=True).encode()).hexdigest()


# ---------- signed requests (PROTOCOL.md, "Signed requests (HMAC)") ----------

def signing_key(api_key):
    """K = HMAC-SHA256(key = UTF-8(apiKey), msg = "stash-manager v1")."""
    return hmac.new(api_key.encode(), b"stash-manager v1", hashlib.sha256).digest()


def request_sig(k, method, path_with_query, ts, nonce, body):
    msg = "%s\n%s\n%d\n%s\n%s" % (method, path_with_query, ts, nonce, hashlib.sha256(body).hexdigest())
    return hmac.new(k, msg.encode(), hashlib.sha256).hexdigest()


def response_sig(k, nonce, status, ts, body):
    msg = "resp\n%s\n%d\n%d\n%s" % (nonce, status, ts, hashlib.sha256(body).hexdigest())
    return hmac.new(k, msg.encode(), hashlib.sha256).hexdigest()


def verify_response(k, nonce, status, header, body, now_ms=None):
    """The checks a bot makes on a response: the header is there, fresh (60 s) and signed for our nonce."""
    if not header:
        return False
    parts = header.split(" ")
    if len(parts) != 3 or parts[0] != "v1" or not parts[1].isdigit():
        return False
    ts = int(parts[1])
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    if abs(now_ms - ts) > 60000:
        return False
    return hmac.compare_digest(parts[2], response_sig(k, nonce, status, ts, body))


class Client:
    """Talks to the server the way the bots do: every request signed (X-SM-Auth), every response verified (X-SM-Sig).
    bearer=True sends the retired Authorization header for negative authentication tests."""

    def __init__(self, url, key, bearer=False):
        self.url = url.rstrip("/")
        self.key = key
        self.credential_id = "-"
        secret = key
        if re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\.[A-Za-z0-9_-]{43}", key):
            self.credential_id, secret = key.split(".", 1)
        self.k = signing_key(secret)
        self.bearer = bearer
        self.last_error = None

    def request(self, method, path, body=None, timeout=15, bot_id=None, ts=None, nonce=None, key=None, raw=False):
        data = b"" if body is None else json.dumps(body).encode()
        headers = {}
        if body is not None:
            headers["Content-Type"] = "application/json"
        nonce = nonce or os.urandom(16).hex()
        if self.bearer:
            headers["Authorization"] = "Bearer " + self.key
        else:
            ts = ts if ts is not None else int(time.time() * 1000)
            k = signing_key(key) if key else self.k
            headers["X-SM-Auth"] = "v1 %s %d %s %s" % (bot_id or self.credential_id, ts, nonce, request_sig(k, method, path, ts, nonce, data))
        req = urllib.request.Request(self.url + path, data=data if method != "GET" else None, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                status, raw_body, sig = r.status, r.read(), r.headers.get("X-SM-Sig")
        except urllib.error.HTTPError as e:
            status, raw_body, sig = e.code, e.read(), e.headers.get("X-SM-Sig")
        except (urllib.error.URLError, OSError) as e:
            return -1, str(e)  # server restarting or unreachable: the caller retries
        if raw:
            return status, raw_body, sig, nonce
        if not self.bearer and status not in (401, 403) and status < 500 and not verify_response(self.k, nonce, status, sig, raw_body):
            # A spoofed server or a man in the middle: the response is thrown away, no jobs from it run
            self.last_error = "server signature invalid"
            return -2, "server signature invalid"
        text = raw_body.decode()
        try:
            return status, (json.loads(text) if text else None)
        except ValueError:
            return status, text

    def get(self, path, timeout=40):
        return self.request("GET", path, timeout=timeout)

    def post(self, path, body):
        if path.startswith("/api/v1/orders/") and isinstance(body, dict):
            body = dict(body, player=PLAYER)  # claims and status reports name the bot (stash ownership)
        return self.request("POST", path, body)


def load_key(args):
    if args.key:
        return args.key
    if os.environ.get("WESORT_MANAGER_KEY"):
        return os.environ["WESORT_MANAGER_KEY"]
    if args.data:
        from pathlib import Path
        root = Path(os.path.abspath(args.data))
        denied = {"qwerte", "qwerte-addon", "stash-manager-data"}
        marker = root / ".wesort-installation"
        config = root / "config.properties"
        if any(p.name.lower() in denied or p.is_symlink() for p in (root, *root.parents)):
            sys.exit("refusing legacy or symlink data path")
        if marker.is_symlink() or config.is_symlink() or marker.read_text() != "wesort-manager:1\n":
            sys.exit("--data must be an isolated WeSort Manager installation")
        with config.open(encoding="utf-8") as f:
            for line in f:
                if line.strip().startswith("apiKey="):
                    return line.split("=", 1)[1].strip()
    sys.exit("no API key: use --key, WESORT_MANAGER_KEY or --data")


def show(label, status, body):
    print(f"{label}: {status} {json.dumps(body) if body is not None else ''}")


# Bot status sent with every heartbeat (PROTOCOL.md, "Additions: delivery status and order size").
DELIVERY = {"x": 120, "y": 64, "z": -340, "dimension": "minecraft:overworld"}
BLOCKER = None
NAMING = ...  # PROTOCOL.md, "Additions: shulker names": ... = not sent (an older bot), None = null, else {anvil, xpLevels}
CRAFTING = ...  # PROTOCOL.md, "Additions: crafting": ... = not sent, None = null, else {enabled, table}


def heartbeat(c, index_hash, extra=None):
    body = {"server": SERVER, "stash": STASH, "player": PLAYER, "indexHash": index_hash,
            "delivery": DELIVERY, "orderBlocker": BLOCKER}
    if NAMING is not ...:
        body["naming"] = NAMING
    if CRAFTING is not ...:
        body["crafting"] = CRAFTING
    body.update(extra or {})
    return c.post("/api/v1/heartbeat", body)


def serve(c, args):
    """Heartbeats every --interval seconds. A rescan request in the reply is acknowledged with rescanDone; then,
    unless --rescan-blocked is set, every container is marked stale and re-read --step at a time, each step pushed
    as a new index (PROTOCOL.md, "Additions: manual rescan")."""
    total = args.containers
    stale = 0
    blocked = args.rescan_blocked
    extra = {"rescanBlocked": blocked}
    end = time.time() + args.seconds
    while time.time() < end:
        index = build_index(extra_diamonds=args.extra_diamonds,
                            containers={"known": total, "total": total, "stale": stale})
        status, body = heartbeat(c, index["indexHash"], extra)
        show("heartbeat", status, body)
        rescan = body.get("rescan") if isinstance(body, dict) else None
        changed = isinstance(body, dict) and bool(body.get("needIndex"))
        if rescan is not None:
            changed = True
            extra["rescanDone"] = rescan
            show("heartbeat (rescanDone)", *heartbeat(c, index["indexHash"], extra))
            if not blocked:
                stale = total
        elif stale > 0:
            stale = max(0, stale - args.step)
            changed = True
        if changed:
            index = build_index(extra_diamonds=args.extra_diamonds,
                                containers={"known": total, "total": total, "stale": stale})
            show(f"index (stale {stale}/{total})", *c.post("/api/v1/index", index))
        time.sleep(args.interval)


# ---------- dashboard bot (DASHBOARD-DESIGN.md phases 2-3) ----------
# A StashConfig-like plugin config: what the real plugin's ConfigSchema would push for scope "plugin".

def delivery_chests(chests, used, unseen):
    """Fake delivery chests in fill order: chest 0 has `used` stacks (named shulkers, an empty one, loose items), the
    others are full; with `unseen`, the last one was never read."""
    kinds = [
        {"id": "minecraft:red_shulker_box", "name": "Gems", "count": 1, "maxStack": 1, "shulker": True,
         "contents": [{"id": "minecraft:diamond", "count": 1728}]},
        {"id": "minecraft:shulker_box", "name": "PvP Kit", "count": 1, "maxStack": 1, "shulker": True,
         "contents": [{"id": "minecraft:diamond_sword", "count": 1}, {"id": "minecraft:golden_apple", "count": 64},
                      {"id": "minecraft:totem_of_undying", "count": 1}]},
        {"id": "minecraft:blue_shulker_box", "name": "Blue Shulker Box", "count": 1, "maxStack": 1, "shulker": True, "contents": []},
        {"id": "minecraft:cobblestone", "name": "Cobblestone", "count": 64, "maxStack": 64},
        {"id": "minecraft:diamond_pickaxe", "name": "<b>Pick \u2026", "count": 1, "maxStack": 1, "damage": 900, "maxDamage": 1561},
    ]
    now = int(time.time() * 1000)
    out = []
    for n in range(chests):
        if n == 0:
            items = [dict(kinds[i % len(kinds)], slot=i) for i in range(used)]
            out.append({"n": 0, "size": 54, "full": used >= 54, "readAt": now - 90_000, "items": items})
        elif unseen and n == chests - 1:
            out.append({"n": n, "size": 27, "full": True, "readAt": 0, "items": None})
        else:
            items = [{"slot": i, "id": "minecraft:white_shulker_box", "name": "Order %d" % n, "count": 1, "maxStack": 1,
                      "shulker": True, "contents": [{"id": "minecraft:oak_log", "count": 1728}]} for i in range(54)]
            out.append({"n": n, "size": 54, "full": True, "readAt": now - 600_000, "items": items})
    return out


def delivery_hash(chests):
    """sha1 of the canonical JSON of the chests without their readAt fields."""
    bare = [{k: v for k, v in c.items() if k != "readAt"} for c in chests]
    return hashlib.sha1(json.dumps(bare, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def delivery(c, args):
    """Heartbeats with deliveryHash; sends POST /api/v1/delivery when the server says needDelivery."""
    chests = delivery_chests(args.chests, args.used, args.unseen)
    h = delivery_hash(chests)
    storage = {"autoExpand": args.auto_expand, "chests": args.chests, "full": max(0, args.chests - 1), "canExpand": args.auto_expand,
               "blocked": None if args.auto_expand else "auto expand is off"}
    end = time.time() + args.seconds
    sample_hash = build_index()["indexHash"]
    while time.time() < end:
        st, body = heartbeat(c, sample_hash, {"deliveryHash": h, "deliveryStorage": storage})
        need = isinstance(body, dict) and body.get("needDelivery")
        show("heartbeat (needDelivery %s)" % need, st, None)
        if need:
            report = {"version": 1, "server": SERVER, "stash": STASH, "player": PLAYER, "hash": h,
                      "readAt": max(ch["readAt"] for ch in chests), "chests": chests}
            show("delivery", *c.post("/api/v1/delivery", report))
        time.sleep(args.interval)


def main():
    global SERVER, STASH, PLAYER, DELIVERY, BLOCKER, NAMING, CRAFTING
    p = argparse.ArgumentParser(description="Fake WeSort addon for the WeSort Manager server")
    p.add_argument("--url", default="http://127.0.0.1:8765")
    p.add_argument("--key")
    p.add_argument("--data", help="server data dir, to read the API key from config.properties")
    p.add_argument("--stash", default=STASH)
    p.add_argument("--server", default=SERVER)
    p.add_argument("--player", default=PLAYER, help="the bot's player name (stash ownership; default FakeBot)")
    p.add_argument("--no-delivery", action="store_true", help="heartbeats say no delivery container is set (delivery: null)")
    p.add_argument("--delivery", help="delivery container as x,y,z[,dimension] (default 120,64,-340,minecraft:overworld)")
    p.add_argument("--blocker", help="orderBlocker to report, e.g. \"no delivery box\" (default: null)")
    p.add_argument("--naming", help="heartbeat naming: anvil:xpLevels such as true:30 or false:0, or null (default: not sent)")
    p.add_argument("--crafting", help="heartbeat crafting: enabled:table such as true:true or true:false, or null (default: not sent)")
    sub = p.add_subparsers(dest="cmd", required=True)
    push = sub.add_parser("push", help="push the sample index")
    push.add_argument("--no-tabs", action="store_true", help="an old-style index without tab/tabOrder/shulkers")
    push.add_argument("--other-tabs", action="store_true", help="like a proxy bot: every item has tab \"other\" (the server fills them in)")
    push.add_argument("--mc", default="26.3")
    push.add_argument("--extra-diamonds", type=int, default=0)
    push.add_argument("--shulker-colours", action="store_true",
                      help="add every shulker colour (empty, filled, mixed) and the other block-entity items")
    sub.add_parser("heartbeat")
    sub.add_parser("claim")
    st = sub.add_parser("status")
    st.add_argument("id")
    st.add_argument("status")
    st.add_argument("--phase")
    st.add_argument("--message")
    st.add_argument("--done", type=int)
    st.add_argument("--total", type=int)
    st.add_argument("--result-shulkers", type=int)
    sv = sub.add_parser("serve", help="send heartbeats for a while and answer rescan requests")
    sv.add_argument("--seconds", type=float, default=120)
    sv.add_argument("--interval", type=float, default=2)
    sv.add_argument("--containers", type=int, default=45, help="containers in the fake stash")
    sv.add_argument("--step", type=int, default=9, help="containers re-read per heartbeat during a rescan")
    sv.add_argument("--rescan-blocked", help="acknowledge rescans but report this reason (e.g. \"sorter off\")")
    sv.add_argument("--extra-diamonds", type=int, default=0)
    dl = sub.add_parser("delivery", help="heartbeats with deliveryHash; send the delivery storage contents when asked")
    dl.add_argument("--seconds", type=float, default=120)
    dl.add_argument("--interval", type=float, default=5)
    dl.add_argument("--chests", type=int, default=1, help="delivery chests (1-16; after the first they are full)")
    dl.add_argument("--used", type=int, default=20, help="stacks already in the first chest (0-54)")
    dl.add_argument("--unseen", action="store_true", help="the last chest was never read (items: null)")
    dl.add_argument("--auto-expand", action="store_true", help="deliveryStorage.autoExpand true")
    run = sub.add_parser("run", help="claim one order and walk it to a finished status")
    run.add_argument("--fail", action="store_true")
    run.add_argument("--steps", type=int, default=3)
    run.add_argument("--delay", type=float, default=0.5)
    args = p.parse_args()

    SERVER, STASH, PLAYER = args.server, args.stash, args.player
    if args.no_delivery:
        DELIVERY = None
    elif args.delivery:
        parts = args.delivery.split(",")
        DELIVERY = {"x": int(parts[0]), "y": int(parts[1]), "z": int(parts[2]),
                    "dimension": parts[3] if len(parts) > 3 else "minecraft:overworld"}
    BLOCKER = args.blocker
    if args.naming == "null":
        NAMING = None
    elif args.naming:
        anvil, _, xp = args.naming.partition(":")
        NAMING = {"anvil": anvil == "true", "xpLevels": int(xp or "-1")}
    if args.crafting == "null":
        CRAFTING = None
    elif args.crafting:
        enabled, _, table = args.crafting.partition(":")
        CRAFTING = {"enabled": enabled == "true", "table": table == "true"}
    c = Client(args.url, load_key(args))
    sample_hash = build_index()["indexHash"]

    if args.cmd == "push":
        index = build_index(tabs=not args.no_tabs, mc=args.mc, extra_diamonds=args.extra_diamonds)
        if args.shulker_colours:
            add_colour_shulkers(index)
        if args.other_tabs:
            for i in index["items"]:
                i["tab"], i["tabOrder"] = "other", 1000000
            index["indexHash"] = hashlib.sha1(json.dumps(index["items"], sort_keys=True).encode()).hexdigest()
        show("index", *c.post("/api/v1/index", index))
    elif args.cmd == "heartbeat":
        show("heartbeat", *heartbeat(c, sample_hash))
    elif args.cmd == "delivery":
        delivery(c, args)
    elif args.cmd == "serve":
        serve(c, args)
    elif args.cmd == "claim":
        show("claim", *c.post("/api/v1/orders/claim", {"server": SERVER, "stash": STASH}))
    elif args.cmd == "status":
        body = {"status": args.status}
        if args.phase is not None:
            body["phase"] = args.phase
        if args.message is not None:
            body["message"] = args.message
        if args.done is not None:
            body["progress"] = {"done": args.done, "total": args.total or args.done}
        if args.result_shulkers is not None:
            body["result"] = {"shulkers": args.result_shulkers, "lines": []}
        show("status", *c.post(f"/api/v1/orders/{args.id}/status", body))
    elif args.cmd == "run":
        status, body = c.post("/api/v1/orders/claim", {"server": SERVER, "stash": STASH})
        show("claim", status, body)
        if status != 200:
            return
        order = body["order"]
        oid = order["id"]
        names = order.get("names")
        if names:
            print("names: " + json.dumps(names))
        total = args.steps
        for step in range(1, total + 1):
            hs, hb = heartbeat(c, sample_hash)
            if oid in hb.get("orders", {}).get("cancel", []):
                show("heartbeat (cancel requested)", hs, hb)
                show("status", *c.post(f"/api/v1/orders/{oid}/status", {
                    "status": "cancelled", "phase": "stopped", "message": "Cancelled from the website."}))
                return
            show("status", *c.post(f"/api/v1/orders/{oid}/status", {
                "status": "in_progress", "phase": f"gathering {step}/{total}",
                "progress": {"done": step, "total": total}}))
            time.sleep(args.delay)
        if args.fail:
            show("status", *c.post(f"/api/v1/orders/{oid}/status", {
                "status": "failed", "message": "Delivery container is full."}))
        else:
            delivered = [{"key": l["key"], "delivered": l["count"]} for l in order["lines"]]
            show("status", *c.post(f"/api/v1/orders/{oid}/status", {
                "status": "delivered", "phase": "done", "progress": {"done": total, "total": total},
                "result": {"shulkers": 1, "lines": delivered}}))



def protocol_observation(index):
    """Synthetic physical-format witness ONLY for isolated protocol smoke tests, never Minecraft evidence."""
    import copy
    import uuid
    value = copy.deepcopy(index)
    dimension = "minecraft:overworld"
    identity = [value['server'], dimension, [0, 64, 0]]
    source = {'id': 'c:' + hashlib.sha256(json.dumps(identity, separators=(',', ':')).encode()).hexdigest(),
        'dimension': dimension, 'parts': [[0, 64, 0]], 'kind': 'minecraft:barrel', 'size': 27,
        'state': 'FRESH', 'session': str(uuid.uuid4()), 'sequence': 1, 'baseRevision': 0, 'epoch': '',
        'observedAt': int(time.time() * 1000), 'fingerprint': hashlib.sha256(json.dumps(value['items'], sort_keys=True).encode()).hexdigest(),
        'items': value['items'], 'shulkers': value.get('shulkers', [])}
    value['physicalVersion'] = 1
    value['physicalSources'] = [source]
    return value

if __name__ == "__main__":
    main()
