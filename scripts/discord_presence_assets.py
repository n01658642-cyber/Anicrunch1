"""Draws the Rich Presence art assets uploaded to the Discord application.

Discord caches an asset key forever — the portal says so itself: "asset keys are not editable once
they are saved" — so the art behind a key can only be changed by deleting it and re-uploading. That
makes a checked-in generator worth more than a checked-in PNG: the next person to want a tweak gets
the source of the thing rather than a binary nobody can edit.

The invite cover is the application's fixed 16:9 face in Discord's developer portal. It pairs the
app's existing icon with the wordmark used in the app header, without asking Discord to scale a
square icon into a wide slot.

Two asset keys are referenced at runtime, and they share one slot: the small circular badge in the
corner of the episode cover art, at roughly 32 px on a phone — which is what every decision below
is really about.
`icon` (APP_ICON_ASSET_KEY) is shown while an episode plays, so the card says who is playing it;
`paused` (PAUSED_ASSET_KEY) replaces it when the episode stops.

    python scripts/discord_presence_assets.py [--out DIR]
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

# Discord recommends 1024x1024 and refuses anything under 512x512.
SIZE = 1024
INVITE_SIZE = (1024, 576)

# The app's own Midnight palette (app/src/main/java/com/anilili/ui/theme/Midnight.kt), so the badge
# belongs to the same app as the screen it was paused from.
PANEL = (11, 8, 16)  # Midnight.Bg/Panel #0B0810
HOVER = (21, 16, 29)  # Midnight.Hover #15101D
PURPLE = (167, 139, 250)  # Midnight.Purple2 #A78BFA
TEXT = (247, 245, 250)  # Midnight.Text #F7F5FA

# Drawn at 4x and downsampled. Pillow's shape drawing has no antialiasing of its own, so a circle
# drawn at final size comes out visibly stepped — supersampling is what makes the edge clean.
SCALE = 4


def _rounded_bar(draw: ImageDraw.ImageDraw, x: int, y: int, w: int, h: int, fill) -> None:
    draw.rounded_rectangle([x, y, x + w, y + h], radius=w // 2.6, fill=fill)


def draw_paused(size: int = SIZE) -> Image.Image:
    """A pause glyph on a dark disc, with a purple rim.

    Transparent outside the circle rather than a filled square: Discord masks the small image to a
    circle in most surfaces but not all of them, and a disc that is already round survives both.
    """
    canvas = size * SCALE
    image = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    # The disc. A vertical gradient would be nicer, but it is invisible at 32 px and costs the
    # clean edge, so this is flat on purpose.
    draw.ellipse([0, 0, canvas - 1, canvas - 1], fill=(*PANEL, 255))

    # A slightly lighter inner face keeps the rim from reading as a flat outline. The rim is kept
    # near the edge on purpose: an inset that looks balanced at full size becomes a muddy dark
    # halo once the whole badge is 32 px across, and eats the contrast that separates it from
    # whatever cover art sits behind.
    inset = int(canvas * 0.028)
    draw.ellipse(
        [inset, inset, canvas - 1 - inset, canvas - 1 - inset],
        fill=(*HOVER, 255),
        outline=(*PURPLE, 255),
        width=int(canvas * 0.030),
    )

    # Two bars, sized so their corners stay well inside the rim. Chunky deliberately: a thin pause
    # glyph disappears at badge size, which is the only size this is ever seen at.
    bar_w = int(canvas * 0.166)
    bar_h = int(canvas * 0.459)
    gap = int(canvas * 0.117)
    top = (canvas - bar_h) // 2
    left = (canvas - (bar_w * 2 + gap)) // 2

    _rounded_bar(draw, left, top, bar_w, bar_h, (*TEXT, 255))
    _rounded_bar(draw, left + bar_w + gap, top, bar_w, bar_h, (*TEXT, 255))

    return image.resize((size, size), Image.LANCZOS)


LOGO = Path("anilili-logo.png")
WORDMARK = Path("app/src/main/res/drawable-nodpi/anilili_header_wordmark.png")

# The mark peaks around 198 luminance and its surround sits at 0-1. Feathering between a floor that
# swallows the near-black noise and a ceiling below the mark keeps the anti-aliased edge clean when
# the cover needs a cutout. Flooding only from the corners preserves the black face/play triangle.
LOGO_ALPHA_FLOOR = 14
LOGO_ALPHA_CEILING = 110


def _draw_mark(size: int) -> Image.Image:
    """Closely crops the mark while preserving its internal black negative space."""
    source = Image.open(LOGO).convert("RGBA")
    luminance = source.convert("L")
    width, height = source.size

    flood = luminance.copy()
    for corner in ((0, 0), (width - 1, 0), (0, height - 1), (width - 1, height - 1)):
        ImageDraw.floodfill(flood, corner, 255, thresh=LOGO_ALPHA_CEILING)
    outside = flood.point(lambda level: 255 if level == 255 else 0)

    lum, out = luminance.load(), outside.load()
    cut = source.copy()
    pixels = cut.load()
    span = LOGO_ALPHA_CEILING - LOGO_ALPHA_FLOOR
    for y in range(height):
        for x in range(width):
            if not out[x, y]:
                continue
            red, green, blue, _ = pixels[x, y]
            level = lum[x, y]
            alpha = 0 if level <= LOGO_ALPHA_FLOOR else int(
                (level - LOGO_ALPHA_FLOOR) / span * 255
            )
            pixels[x, y] = (red, green, blue, max(0, min(255, alpha)))

    cut = cut.crop(cut.getbbox())

    # Square the close crop with a little air so Discord's circular badge mask never clips the
    # outer hair/swoosh tips.
    side = int(max(cut.size) * 1.06)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(cut, ((side - cut.size[0]) // 2, (side - cut.size[1]) // 2))
    return canvas.resize((size, size), Image.LANCZOS)


def draw_icon(size: int = SIZE) -> Image.Image:
    """The app's closely cropped mark on the requested solid black square."""
    image = Image.new("RGBA", (size, size), (0, 0, 0, 255))
    image.alpha_composite(_draw_mark(size))
    return image.convert("RGB")


def _contain(source: Image.Image, bounds: tuple[int, int]) -> Image.Image:
    """Scales an image into bounds without changing its aspect ratio."""
    image = source.copy()
    image.thumbnail(bounds, Image.LANCZOS)
    return image


def draw_invite() -> Image.Image:
    """The 16:9 image shown above Discord Rich Presence chat invites.

    The exact checked-in icon and wordmark are composited rather than redrawn. Nothing competes
    with them: the background is solid black, with no glow, gradient, vignette or decoration.
    """
    _, height = INVITE_SIZE
    image = Image.new("RGBA", INVITE_SIZE, (0, 0, 0, 255))

    icon = _draw_mark(430)
    image.alpha_composite(icon, (35, (height - icon.height) // 2))

    wordmark = _contain(Image.open(WORDMARK).convert("RGBA"), (500, 150))
    wordmark_x = 474 + (500 - wordmark.width) // 2
    wordmark_y = (height - wordmark.height) // 2
    image.alpha_composite(wordmark, (wordmark_x, wordmark_y))

    return image.convert("RGB")


ASSETS = {
    "invite-cover": (draw_invite, INVITE_SIZE, "Rich Presence Invite Image"),
    "icon": (draw_icon, (SIZE, SIZE), "Rich Presence asset key: icon"),
    "paused": (draw_paused, (SIZE, SIZE), "Rich Presence asset key: paused"),
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("design/discord-presence"),
        help="where the PNGs are written (default: design/discord-presence)",
    )
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    for key, (render, expected_size, destination) in ASSETS.items():
        path = args.out / f"{key}.png"
        image = render()
        if image.size != expected_size:
            raise ValueError(f"{key} rendered at {image.size}, expected {expected_size}")
        # Do not churn a checked-in PNG merely because a different Pillow build chooses different
        # compression bytes for the same pixels.
        unchanged = False
        if path.exists():
            with Image.open(path) as existing:
                comparable = existing.convert(image.mode)
                unchanged = comparable.size == image.size and ImageChops.difference(
                    comparable,
                    image,
                ).getbbox() is None
        if not unchanged:
            image.save(path, "PNG")
        width, height = image.size
        print(f"{path}  ({width}x{height})  {destination}")


if __name__ == "__main__":
    main()
