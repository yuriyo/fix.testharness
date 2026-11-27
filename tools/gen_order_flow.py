#!/usr/bin/env python3
"""Generate a realistic mixed order-flow scenario for fix.testharness.

Mix: passive limit orders (GTC/DAY) around a drifting mid, aggressive orders
(IOC/FOK/DAY) that cross the book, market orders, cancels and cancel/replaces.
A small order-book model keeps every cancel and replace aimed at an order that
is still resting, so the scenario never depends on luck. The model also counts
the trades it expects, so the simulator's trade count can be checked afterwards.

Usage: gen_order_flow.py <out.yml> [--orders N] [--seed S]
"""
import argparse
import random
from dataclasses import dataclass, field

SYMBOLS = {  # symbol: starting mid
    "AAPL": 150.00,
    "MSFT": 400.00,
    "NVDA": 900.00,
    "TSLA": 250.00,
}
TICK = 0.01


@dataclass
class Order:
    clordid: str
    symbol: str
    side: str  # BUY | SELL
    px: float
    qty: int
    tif: str
    seq: int = 0


@dataclass
class Book:
    bids: list = field(default_factory=list)  # resting buys
    asks: list = field(default_factory=list)  # resting sells

    def side(self, s):
        return self.bids if s == "BUY" else self.asks

    def opposite(self, s):
        return self.asks if s == "BUY" else self.bids

    def best_opposite_key(self, s):
        """Price-time priority: buys take the lowest ask first, sells the highest bid first."""
        opp = self.opposite(s)
        if s == "BUY":
            return sorted(opp, key=lambda o: (o.px, o.seq))
        return sorted(opp, key=lambda o: (-o.px, o.seq))


def crosses(side, px, resting_px):
    return px >= resting_px if side == "BUY" else px <= resting_px


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--orders", type=int, default=3000)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    rnd = random.Random(args.seed)

    mids = dict(SYMBOLS)
    books = {s: Book() for s in SYMBOLS}
    steps = []
    counter = 0
    seq = 0
    stats = {"new": 0, "cancel": 0, "replace": 0, "trades": 0, "fills_qty": 0, "market": 0, "rejects_expected": 0}

    def r(x):
        return round(x, 2)

    def new_id(prefix):
        nonlocal counter
        counter += 1
        return f"{prefix}-{counter:05d}"

    def open_orders():
        return [o for b in books.values() for o in (b.bids + b.asks)]

    def add_step(text):
        steps.append(text)

    def match_and_rest(order, book, kind):
        """Match an incoming order against the book, then rest or cancel the remainder."""
        remaining = order.qty
        # FOK: all or nothing; check liquidity first
        if kind == "FOK":
            avail = 0
            for o in book.best_opposite_key(order.side):
                if not crosses(order.side, order.px, o.px):
                    break
                avail += o.qty
            if avail < remaining:
                return  # cancelled, book untouched
        for o in list(book.best_opposite_key(order.side)):
            if remaining <= 0:
                break
            if not crosses(order.side, order.px, o.px):
                break
            fill = min(remaining, o.qty)
            remaining -= fill
            o.qty -= fill
            stats["trades"] += 1
            stats["fills_qty"] += fill
            if o.qty == 0:
                book.opposite(order.side).remove(o)
        if remaining > 0 and kind in ("GTC", "DAY"):
            order.qty = remaining
            book.side(order.side).append(order)

    while stats["new"] < args.orders:
        # Mid drifts slowly, so passive orders stay near the market
        sym = rnd.choice(list(SYMBOLS))
        if rnd.random() < 0.05:
            mids[sym] = r(mids[sym] + rnd.choice([-1, 1]) * rnd.randint(1, 5) * TICK)
        mid = mids[sym]
        book = books[sym]
        roll = rnd.random()

        if roll < 0.08 and open_orders():
            # cancel one of our resting orders
            o = rnd.choice(open_orders())
            books[o.symbol].side(o.side).remove(o)
            cxl = new_id("CXL")
            add_step(
                f'  - type: cancel\n    clOrdId: "{cxl}"\n    origClOrdId: "{o.clordid}"\n'
                f'    symbol: "{o.symbol}"\n    side: {o.side}\n'
            )
            stats["cancel"] += 1
            continue

        if roll < 0.15 and open_orders():
            # cancel/replace: new price (1-3 ticks) and/or quantity; priority is lost
            o = rnd.choice(open_orders())
            books[o.symbol].side(o.side).remove(o)
            sym = o.symbol
            book = books[sym]
            shift = rnd.randint(1, 3) * TICK * (1 if o.side == "BUY" else -1) * rnd.choice([-1, 1])
            newpx = r(o.px + shift)
            if newpx <= 0:
                newpx = o.px
            newqty = max(100, int(round(o.qty * rnd.choice([0.5, 1.0, 1.5, 2.0]) / 100.0)) * 100)
            rep = new_id("REP")
            replaced = Order(rep, sym, o.side, newpx, newqty, o.tif)
            seq += 1
            replaced.seq = seq
            add_step(
                f'  - type: replace\n    clOrdId: "{rep}"\n    origClOrdId: "{o.clordid}"\n'
                f'    symbol: "{sym}"\n    side: {o.side}\n    ordType: LIMIT\n'
                f'    qty: {newqty}\n    price: {newpx:.2f}\n    tif: {o.tif}\n'
            )
            stats["replace"] += 1
            match_and_rest(replaced, book, replaced.tif)
            continue

        # new orders
        side = rnd.choice(["BUY", "SELL"])
        qty = rnd.choice([100, 100, 200, 300, 500, 1000])
        if roll < 0.18:  # market order
            cl = new_id("MKT")
            add_step(
                f'  - type: new_order\n    clOrdId: "{cl}"\n    symbol: "{sym}"\n    side: {side}\n'
                f'    ordType: MARKET\n    qty: {qty}\n    tif: DAY\n'
            )
            stats["new"] += 1
            stats["market"] += 1
            # market order: sweep available liquidity at any price, cancel the rest
            mo = Order(cl, sym, side, mid + (100 if side == "BUY" else -100), qty, "IOC")
            match_and_rest(mo, book, "IOC")
            continue

        if roll < 0.30:  # aggressive: crosses the book
            tif = rnd.choices(["IOC", "FOK", "DAY"], weights=[70, 10, 20])[0]
            ticks = rnd.randint(0, 3)
            px = r(mid + (ticks * TICK if side == "BUY" else -ticks * TICK))
        else:  # passive: rests away from the touch
            tif = rnd.choices(["GTC", "DAY"], weights=[40, 60])[0]
            ticks = rnd.randint(1, 40)
            px = r(mid - ticks * TICK if side == "BUY" else mid + ticks * TICK)
        if px <= 0:
            continue
        cl = new_id("ORD")
        seq += 1
        o = Order(cl, sym, side, px, qty, tif, seq)
        add_step(
            f'  - type: new_order\n    clOrdId: "{cl}"\n    symbol: "{sym}"\n    side: {side}\n'
            f'    ordType: LIMIT\n    qty: {qty}\n    price: {px:.2f}\n    tif: {tif}\n'
        )
        stats["new"] += 1
        match_and_rest(o, book, tif)

    # Clean up: cancel everything still resting so the book is empty afterwards
    for o in open_orders():
        cxl = new_id("END")
        add_step(
            f'  - type: cancel\n    clOrdId: "{cxl}"\n    origClOrdId: "{o.clordid}"\n'
            f'    symbol: "{o.symbol}"\n    side: {o.side}\n'
        )
        stats["cancel"] += 1
    for b in books.values():
        b.bids.clear()
        b.asks.clear()

    header = (
        'name: "Realistic order flow"\n'
        'description: >\n'
        f'  Generated mixed flow: {stats["new"]} new orders (passive GTC/DAY, aggressive IOC/FOK/DAY,\n'
        f'  market), cancels and cancel/replaces on resting orders, over AAPL, MSFT, NVDA and TSLA.\n'
        f'  Generated with seed {args.seed}. Expected trades: {stats["trades"]}.\n'
        '  The book is cleared at the end. Start from an empty simulator for exact counts.\n\n'
        'steps:\n'
    )
    with open(args.out, "w") as f:
        f.write(header)
        f.write("\n".join(steps))
        f.write("\n")
    print(f"wrote {args.out}: steps={len(steps)} new={stats['new']} market={stats['market']} "
          f"cancel={stats['cancel']} replace={stats['replace']} expected_trades={stats['trades']} "
          f"fill_qty={stats['fills_qty']}")


if __name__ == "__main__":
    main()
