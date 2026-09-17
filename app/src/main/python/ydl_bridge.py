import json
import sys
import os


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
    return f"{clean[:80]}.{ext}"


def _base_opts():
    return {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "socket_timeout": 15,
        "retries": 2,
    }


def extract_info(url: str) -> str:
    try:
        import yt_dlp

        with yt_dlp.YoutubeDL(_base_opts()) as ydl:
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
                "direct_url": None,
                "merged": True,
                "filename": _safe_filename(info.get("title", "video"), "mp4"),
            })

        video_only = []
        seen_v = set()
        for v in sorted(video_formats, key=lambda f: f.get("height") or 0, reverse=True):
            if v.get("acodec") and v["acodec"] != "none":
                continue
            key = f"{v.get('height')}_{v.get('ext')}"
            if key in seen_v:
                continue
            seen_v.add(key)
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

        thumbnails = info.get("thumbnails") or []
        thumbnail = info.get("thumbnail")
        if thumbnails:
            best = max(
                (t for t in thumbnails if t.get("url") and t.get("width")),
                key=lambda t: t.get("width", 0),
                default=None,
            )
            if best:
                thumbnail = best["url"]

        return json.dumps({
            "title": info.get("title", ""),
            "channel": info.get("channel") or info.get("uploader"),
            "duration_str": _seconds_to_hms(info["duration"]) if info.get("duration") else None,
            "thumbnail": thumbnail,
            "view_count_str": _fmt_views(view_count) if view_count else None,
            "upload_date": info.get("upload_date"),
            "webpage_url": info.get("webpage_url") or url,
            "formats": merged + video_only + audio_only,
        })

    except Exception as e:
        return json.dumps({"error": str(e)})


def search_youtube(query: str, max_results: int = 10) -> str:
    try:
        import yt_dlp

        opts = {**_base_opts(), "extract_flat": "in_playlist"}

        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(f"ytsearch{max_results}:{query}", download=False)

        entries = info.get("entries", [])
        results = []
        for v in entries:
            if not v:
                continue
            vid_id = v.get("id", "")
            view_count = v.get("view_count")

            thumbnail = v.get("thumbnail")
            if not thumbnail and vid_id:
                thumbnail = f"https://i.ytimg.com/vi/{vid_id}/hqdefault.jpg"

            results.append({
                "id": vid_id,
                "url": f"https://www.youtube.com/watch?v={vid_id}",
                "title": v.get("title", ""),
                "channel": v.get("channel") or v.get("uploader"),
                "duration_str": _seconds_to_hms(v["duration"]) if v.get("duration") else None,
                "view_count_str": _fmt_views(view_count) if view_count else None,
                "thumbnail": thumbnail,
            })

        return json.dumps({"results": results})

    except Exception as e:
        return json.dumps({"error": str(e)})


def resolve_merged_urls(url: str, format_id: str) -> str:
    try:
        import yt_dlp

        video_fmt_id, audio_fmt_id = format_id.split("+", 1)

        opts = {**_base_opts(), "format": format_id}

        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)

        formats = {f["format_id"]: f for f in info.get("formats", [])}

        video = formats.get(video_fmt_id)
        audio = formats.get(audio_fmt_id)

        if not video or not audio:
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
    try:
        import yt_dlp
        return yt_dlp.version.__version__
    except Exception as e:
        return json.dumps({"error": str(e)})
