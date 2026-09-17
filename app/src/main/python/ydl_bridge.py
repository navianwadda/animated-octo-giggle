# ydl_bridge.py
# Shared Python bridge for both V1 (bundled) and V2 (auto-update).
# Called from Kotlin via Chaquopy. Never import this directly —
# always call through YdlBridge.kt which handles threading and errors.

import json
import sys
import os

# ── Helpers ──────────────────────────────────────────────────────────────────

def _seconds_to_hms(total: int) -> str:
    h = total // 3600
    m = (total % 3600) // 60
    s = total % 60
    if h > 0:
        return f"{h}:{m:02d}:{s:02d}"
    return f"{m}:{s:02d}"


def _fmt_views(n: int) -> str:
    if n >= 1_000_000_000:
        return f"{n / 1_000_000_000:.1f}B views"
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f}M views"
    if n >= 1_000:
        return f"{n / 1_000:.0f}K views"
    return f"{n} views"


def _safe_filename(title: str, ext: str) -> str:
    import re
    clean = re.sub(r'[^\w\s\-.]', '_', title)
    return f"{clean}.{ext}"


# ── Core extraction ───────────────────────────────────────────────────────────

def extract_info(url: str) -> str:
    """
    Extract video metadata and available formats from a URL.
    Returns JSON string:
      { title, channel, duration_str, thumbnail, view_count_str,
        upload_date, formats: [VideoFormat] }
    or { error: str } on failure.
    """
    try:
        import yt_dlp

        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
        }

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)

        raw_formats = info.get("formats", [])

        video_formats = [
            f for f in raw_formats
            if f.get("vcodec") and f["vcodec"] != "none" and f.get("height")
        ]
        audio_formats = [
            f for f in raw_formats
            if f.get("acodec") and f["acodec"] != "none"
            and (not f.get("vcodec") or f["vcodec"] == "none")
        ]

        best_audio = sorted(audio_formats, key=lambda f: f.get("abr") or 0, reverse=True)
        best_audio = best_audio[0] if best_audio else None

        # Video + Audio (merged) — one per unique height
        merged = []
        seen_heights = set()
        for v in sorted(video_formats, key=lambda f: f.get("height") or 0, reverse=True):
            h = v.get("height")
            if not h or h in seen_heights:
                continue
            seen_heights.add(h)
            merged.append({
                "type": "video+audio",
                "quality": f"{h}p",
                "height": h,
                "fps": v.get("fps"),
                "ext": "mp4",
                "vcodec": v.get("vcodec"),
                "acodec": best_audio.get("acodec") if best_audio else None,
                "filesize": None,
                "format_id": f"{v['format_id']}+{best_audio['format_id'] if best_audio else 'bestaudio'}",
                "direct_url": None,   # merged formats need ffmpeg; no single URL
                "merged": True,
                "filename": _safe_filename(info.get("title", "video"), "mp4"),
            })

        # Video only
        video_only = []
        for v in sorted(video_formats, key=lambda f: f.get("height") or 0, reverse=True):
            if v.get("acodec") and v["acodec"] != "none":
                continue
            video_only.append({
                "type": "video-only",
                "quality": f"{v.get('height')}p",
                "height": v.get("height"),
                "fps": v.get("fps"),
                "ext": v.get("ext", "mp4"),
                "vcodec": v.get("vcodec"),
                "filesize": v.get("filesize") or v.get("filesize_approx"),
                "format_id": v["format_id"],
                "direct_url": v.get("url"),
                "merged": False,
                "filename": _safe_filename(info.get("title", "video"), v.get("ext", "mp4")),
            })

        # Audio only
        audio_only = []
        seen_audio = set()
        for a in sorted(audio_formats, key=lambda f: f.get("abr") or 0, reverse=True):
            key = f"{a.get('abr')}_{a.get('ext')}"
            if key in seen_audio:
                continue
            seen_audio.add(key)
            audio_only.append({
                "type": "audio-only",
                "quality": f"{a['abr']}kbps" if a.get("abr") else a["format_id"],
                "ext": a.get("ext", "m4a"),
                "acodec": a.get("acodec"),
                "abr": a.get("abr"),
                "filesize": a.get("filesize") or a.get("filesize_approx"),
                "format_id": a["format_id"],
                "direct_url": a.get("url"),
                "merged": False,
                "filename": _safe_filename(info.get("title", "video"), a.get("ext", "m4a")),
            })

        view_count = info.get("view_count")

        return json.dumps({
            "title": info.get("title", ""),
            "channel": info.get("channel") or info.get("uploader"),
            "duration_str": _seconds_to_hms(info["duration"]) if info.get("duration") else None,
            "thumbnail": info.get("thumbnail"),
            "view_count_str": _fmt_views(view_count) if view_count else None,
            "upload_date": info.get("upload_date"),
            "formats": merged + video_only + audio_only,
        })

    except Exception as e:
        return json.dumps({"error": str(e)})


def search_youtube(query: str, max_results: int = 10) -> str:
    """
    Search YouTube. Returns JSON string:
      { results: [SearchResult] } or { error: str }
    """
    try:
        import yt_dlp

        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "extract_flat": True,
        }

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(f"ytsearch{max_results}:{query}", download=False)

        entries = info.get("entries", [])
        results = []
        for v in entries:
            if not v:
                continue
            view_count = v.get("view_count")
            results.append({
                "id": v.get("id", ""),
                "url": f"https://www.youtube.com/watch?v={v.get('id', '')}",
                "title": v.get("title", ""),
                "channel": v.get("channel") or v.get("uploader"),
                "duration_str": _seconds_to_hms(v["duration"]) if v.get("duration") else None,
                "view_count_str": _fmt_views(view_count) if view_count else None,
                "thumbnail": v.get("thumbnail"),
            })

        return json.dumps({"results": results})

    except Exception as e:
        return json.dumps({"error": str(e)})


def resolve_merged_urls(url: str, format_id: str) -> str:
    """
    For a merged (video+audio) format, resolve the two direct URLs
    so Kotlin can pass them to ffmpeg-kit without re-running yt-dlp.
    Returns JSON: { video_url, audio_url } or { error }
    """
    try:
        import yt_dlp

        video_fmt_id, audio_fmt_id = format_id.split("+", 1)

        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "format": format_id,
        }

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)

        formats = {f["format_id"]: f for f in info.get("formats", [])}

        video = formats.get(video_fmt_id)
        audio = formats.get(audio_fmt_id)

        if not video or not audio:
            # Fallback: pick best available
            video_formats = [
                f for f in info.get("formats", [])
                if f.get("vcodec") and f["vcodec"] != "none" and f.get("height")
            ]
            audio_formats = [
                f for f in info.get("formats", [])
                if f.get("acodec") and f["acodec"] != "none"
                and (not f.get("vcodec") or f["vcodec"] == "none")
            ]
            video = sorted(video_formats, key=lambda f: f.get("height") or 0, reverse=True)[0]
            audio = sorted(audio_formats, key=lambda f: f.get("abr") or 0, reverse=True)[0]

        return json.dumps({
            "video_url": video["url"],
            "audio_url": audio["url"],
            "ext": "mp4",
        })

    except Exception as e:
        return json.dumps({"error": str(e)})


def get_version() -> str:
    """Return installed yt-dlp version string."""
    try:
        import yt_dlp
        return yt_dlp.version.__version__
    except Exception as e:
        return json.dumps({"error": str(e)})
