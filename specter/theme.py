"""Centralized UI theme — high-contrast, readable on light OR dark terminals.

Same rule as persona-swapper: bright foregrounds on the default background; reverse-video
chips only for short single words where contrast is guaranteed.
"""
from rich.theme import Theme

THEME = Theme({
    "brand": "bold magenta",
    "muted": "grey50",
    "key": "grey62",
    "val": "default",
    "good": "bold green",
    "warn": "yellow",
    "bad": "bold red",
    "fresh": "bold green",
    "unique": "cyan",
    "device": "bright_blue",
    "chip.new": "bold black on green",
    "chip.push": "bold black on cyan",
    "chip.warn": "bold black on yellow",
    "chip.err": "bold white on red",
})


def chip(label: str, style: str) -> str:
    return f"[{style}] {label} [/]"
