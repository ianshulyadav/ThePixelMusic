import os
import subprocess
import html
import json
import time
import sys

def send_apk(url, apk_path, display_name, cap, chat_id, thread_id, bot_token, retries=3):
    if not os.path.exists(apk_path):
        print(f"ERROR: APK not found: {apk_path}", flush=True)
        sys.exit(1)

    size_mb = os.path.getsize(apk_path) / (1024 * 1024)
    print(f"Sending {apk_path} ({size_mb:.1f} MB) as {display_name}...", flush=True)

    args = [
        "curl", "-s",
        "-F", f"document=@{apk_path};filename={display_name}",
        "--form-string", f"chat_id={chat_id}",
        "--form-string", "parse_mode=HTML",
    ]
    if cap:
        args += ["--form-string", f"caption={cap}"]
    if thread_id:
        args += ["--form-string", f"message_thread_id={thread_id}"]
    args.append(url)

    for attempt in range(1, retries + 1):
        result = subprocess.run(args, capture_output=True, text=True)
        if result.stderr:
            print(f"  curl stderr: {result.stderr}", flush=True)

        try:
            response = json.loads(result.stdout)
        except json.JSONDecodeError:
            print(f"  Attempt {attempt}: invalid JSON response: {result.stdout[:200]}", flush=True)
            if attempt < retries:
                time.sleep(5 * attempt)
                continue
            sys.exit(1)

        if response.get("ok"):
            print(f"  OK — sent {display_name}", flush=True)
            return
        else:
            err = response.get("description", "unknown error")
            print(f"  Attempt {attempt} FAILED: {err}", flush=True)
            if attempt < retries:
                time.sleep(5 * attempt)
            else:
                print(f"ERROR: Failed to send {display_name} after {retries} attempts.", flush=True)
                sys.exit(1)


def main():
    bot_token  = os.environ["TELEGRAM_BOT_TOKEN"]
    chat_id    = os.environ["TELEGRAM_CHAT_ID"]
    thread_id  = os.environ.get("TELEGRAM_THREAD_ID", "")
    version    = os.environ["VERSION_NAME"]
    commit_sha = os.environ["COMMIT_SHA"]

    try:
        commit_author  = subprocess.check_output(["git", "log", "-1", "--pretty=format:%an"]).decode("utf-8").strip()
        commit_message = subprocess.check_output(["git", "log", "-1", "--pretty=format:%B"]).decode("utf-8").strip()
        commit_message = "\n".join([line for line in commit_message.split("\n") if line.strip()])
    except Exception:
        commit_author  = "Unknown"
        commit_message = "New release build"

    commit_author  = html.escape(commit_author)
    commit_message = html.escape(commit_message)

    caption = (
        f"<b>PixelMusic v{html.escape(version)}</b>\n"
        f"Commit by: {commit_author}\n"
        f"Commit message:\n<blockquote>{commit_message}</blockquote>\n"
        f"Commit hash: #{commit_sha[:7]}\n"
        f"Device: mobile, wearos\n"
        f"ABI: arm64, armeabi, universal, x86_64\n"
        f"Files: 5\n"
        f"Version: Android >= 11"
    )

    apks = [
        ("wear/build/outputs/apk/release/wear-release.apk",              "app-wearos-release.apk",         caption),
        ("app/build/outputs/apk/release/app-arm64-v8a-release.apk",      "app-mobile-arm64-release.apk",   ""),
        ("app/build/outputs/apk/release/app-armeabi-v7a-release.apk",    "app-mobile-armeabi-release.apk", ""),
        ("app/build/outputs/apk/release/app-x86_64-release.apk",         "app-mobile-x86_64-release.apk",  ""),
        ("app/build/outputs/apk/release/app-universal-release.apk",      "app-mobile-universal-release.apk", ""),
    ]

    url = f"https://api.telegram.org/bot{bot_token}/sendDocument"

    for i, (apk_path, display_name, cap) in enumerate(apks):
        send_apk(url, apk_path, display_name, cap, chat_id, thread_id, bot_token)
        # Avoid Telegram rate limits between uploads
        if i < len(apks) - 1:
            time.sleep(2)

    print("All APKs published successfully.", flush=True)


if __name__ == "__main__":
    main()
