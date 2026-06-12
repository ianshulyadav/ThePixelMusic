import subprocess
import html
import os
import re

def get_current_version():
    properties_path = "gradle.properties"
    if not os.path.exists(properties_path):
        return None
    with open(properties_path, "r", encoding="utf-8") as f:
        for line in f:
            if line.strip().startswith("APP_VERSION_NAME="):
                return line.strip().split("=")[1].strip()
    return None

def get_changelog_from_file(version):
    changelog_path = "CHANGELOG.md"
    if not os.path.exists(changelog_path):
        return None
    with open(changelog_path, "r", encoding="utf-8") as f:
        content = f.read()
    pattern = rf"(##\s*\[{re.escape(version)}\].*?)(?=##\s*\[|\Z)"
    match = re.search(pattern, content, re.DOTALL | re.IGNORECASE)
    if match:
        section = match.group(1).strip()
        lines = section.splitlines()
        if lines:
            body_lines = lines[1:]
            return "\n".join(body_lines).strip()
    return None

def clean_for_telegram(markdown_text):
    lines = []
    raw_lines = markdown_text.splitlines()
    i = 0
    while i < len(raw_lines):
        line = raw_lines[i].strip()
        if not line or line.startswith("###"):
            i += 1
            continue
        if line.startswith("-"):
            text = line.lstrip("-").strip().replace("**", "")
            if text.endswith(":") and i + 1 < len(raw_lines) and raw_lines[i+1].startswith((" ", "\t")) and raw_lines[i+1].strip().startswith("-"):
                next_line = raw_lines[i+1].strip()
                sub_text = next_line.lstrip("-").strip()
                text = f"{text} {sub_text}"
                i += 2
                while i < len(raw_lines) and raw_lines[i].startswith((" ", "\t")) and raw_lines[i].strip().startswith("-"):
                    i += 1
                lines.append(text)
            else:
                lines.append(text)
                i += 1
        else:
            i += 1
    return "\n".join(lines)

def main():
    version = get_current_version()
    print(f"Detected version: {version}")
    
    changelog_md = ""
    changelog_html = ""
    
    if version:
        md_content = get_changelog_from_file(version)
        if md_content:
            print("Successfully extracted changelog from CHANGELOG.md")
            changelog_md = md_content
            # Format clean list of lines for Telegram
            changelog_html = clean_for_telegram(md_content)
            
    # Fallback to git log if CHANGELOG.md extraction fails
    if not changelog_md:
        print("Fallback: Using git log for changelog generation")
        prev_sha = os.environ.get('PREV_SHA', '')
        curr_sha = os.environ.get('CURR_SHA', '')
        if prev_sha and prev_sha != '0000000000000000000000000000000000000000':
            cmd = ['git', 'log', '--pretty=format:%s', f'{prev_sha}..{curr_sha}']
        else:
            cmd = ['git', 'log', '-n', '20', '--pretty=format:%s']
            
        try:
            output = subprocess.check_output(cmd).decode('utf-8', errors='ignore')
            raw_commits = [line.strip() for line in output.split('\n') if line.strip()]
        except Exception:
            raw_commits = ['New release build']
            
        ignored_patterns = [
            r"^ci:", r"^workflow", r"telegram", r"github", r"gitHub", r"Telegram", r"GitHub",
            r"dependabot", r"bump the github-actions", r"bump the gradle-dependencies", r"\[skip ci\]"
        ]
        
        commits = []
        for c in raw_commits:
            if any(re.search(pat, c, re.IGNORECASE) for pat in ignored_patterns):
                continue
            commits.append(c)
            
        if not commits:
            commits = ['Performance enhancements and bug fixes']
            
        changelog_html = '\n'.join(commits)
        changelog_md = '\n'.join('- ' + c for c in commits)
    
    # Wrap HTML in <blockquote> tags for Telegram formatting compatibility
    changelog_html_wrapped = '<blockquote>' + '\n'.join(html.escape(c) for c in changelog_html.splitlines()) + '</blockquote>'
        
    github_output = os.environ.get('GITHUB_OUTPUT')
    if github_output:
        with open(github_output, 'a', encoding='utf-8') as f:
            f.write('changelog_html<<EOF\n' + changelog_html_wrapped + '\nEOF\n')
            f.write('changelog_md<<EOF\n' + changelog_md + '\nEOF\n')
    else:
        print("GITHUB_OUTPUT not set. Changelog HTML:\n", changelog_html_wrapped)
        print("Changelog MD:\n", changelog_md)

if __name__ == '__main__':
    main()
