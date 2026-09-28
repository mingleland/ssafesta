"""develop 위에 이력 보존본(project-history/, tools/history-migration/, README.md)을 더하는 커밋 하나를 워킹트리 없이 만든다.

사용법: python make_archive_commit.py <git 저장소> <branch> <project-history 디렉터리> <tools 디렉터리> <README 파일> <메시지>
"""
import os
import subprocess
import sys
import tempfile

repo, branch, hist, tools_dir, readme, msg = sys.argv[1:7]
env = dict(os.environ, GIT_INDEX_FILE=os.path.join(tempfile.mkdtemp(), "index"),
           GIT_AUTHOR_NAME="colosair", GIT_AUTHOR_EMAIL="210623530+colosair@users.noreply.github.com",
           GIT_COMMITTER_NAME="colosair", GIT_COMMITTER_EMAIL="210623530+colosair@users.noreply.github.com")


def git(*a, inp=None):
    r = subprocess.run(["git", "-C", repo, *a], input=inp, capture_output=True, env=env)
    if r.returncode:
        raise SystemExit(r.stderr.decode("utf-8", "replace"))
    return r.stdout.decode().strip()


parent = git("rev-parse", f"refs/heads/{branch}")
git("read-tree", parent)
entries = []
def add(src, dst):
    sha = git("hash-object", "-w", "--no-filters", src)
    entries.append(f"100644 {sha}\t{dst}")
for root, _, files in os.walk(hist):
    for f in files:
        p = os.path.join(root, f)
        add(p, "project-history/" + os.path.relpath(p, hist).replace("\\", "/"))
for f in sorted(os.listdir(tools_dir)):
    if f.endswith((".py", ".ps1")) or f == "people.json":
        add(os.path.join(tools_dir, f), "tools/history-migration/" + f)
add(readme, "README.md")
git("update-index", "--index-info", inp=("\n".join(entries) + "\n").encode("utf-8"))
tree = git("write-tree")
commit = git("commit-tree", tree, "-p", parent, "-m", msg)
git("update-ref", f"refs/heads/{branch}", commit, parent)
print(commit, len(entries))

