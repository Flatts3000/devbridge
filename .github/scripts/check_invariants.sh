#!/bin/sh
# Enforces the three security invariants from SECURITY.md as an executable check.
#
# devbridge executes arbitrary commands. Its entire security boundary is that the socket is
# unreachable from anywhere but this machine, that it does not exist unless explicitly asked for,
# and that the jar never becomes a dependency of anything released. Those rules have lived in prose
# since the first commit, and prose does not fail a build. This does.
#
# The greps are deliberately biased toward false positives. Splitting the ServerSocket constructor
# across two lines will fail this check even though the code is still correct. That is the right
# direction to be wrong in: a check that refuses an innocent reformat costs somebody a minute, and a
# check that misses a real regression publishes an arbitrary-command endpoint to the network.
#
# Run it locally before opening a PR:  sh .github/scripts/check_invariants.sh

set -eu

status=0
src="src/main/java"

fail() {
    echo "FAIL: $1" >&2
    status=1
}

# Drops `path:NN:` lines whose content begins a comment. The class docs quote the forbidden forms in
# order to warn about them, and a checker that trips over the warning is a checker nobody keeps.
code_only() {
    grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)' || true
}

# ---------------------------------------------------------------------------
# 1. Loopback only.
#
# `new ServerSocket(port)` and `new ServerSocket(port, backlog)` both bind every interface, which on
# a laptop on a shared network hands an arbitrary-command endpoint to everyone on it. The only
# accepted form names the loopback address explicitly on the same line.
# ---------------------------------------------------------------------------
offenders=$(grep -rn --include='*.java' 'new ServerSocket(' "$src" \
    | grep -v 'InetAddress.getLoopbackAddress()' | code_only)
if [ -n "$offenders" ]; then
    echo "$offenders" >&2
    fail "ServerSocket constructed without InetAddress.getLoopbackAddress() on the same line."
fi

# A ServerSocket that was constructed correctly can still be rebound afterwards.
offenders=$(grep -rn --include='*.java' '\.bind(' "$src" | code_only)
if [ -n "$offenders" ]; then
    echo "$offenders" >&2
    fail "explicit bind() call: the bind address must come from the constructor and nowhere else."
fi

# ---------------------------------------------------------------------------
# 2. No wildcard or resolved-hostname bind, in any spelling.
# ---------------------------------------------------------------------------
offenders=$(grep -rn --include='*.java' \
    -e '0\.0\.0\.0' \
    -e 'anyLocalAddress' \
    -e 'getAllByName' \
    -e 'getLocalHost' \
    "$src" | code_only)
if [ -n "$offenders" ]; then
    echo "$offenders" >&2
    fail "wildcard or hostname-resolved bind address referenced."
fi

# ---------------------------------------------------------------------------
# 3. Never shippable.
#
# The reason this is a separate jar rather than a source set is that a separate jar cannot end up in
# a release by accident. A maven-publish block undoes that in one line by making it resolvable, and
# from there it is one `runtimeOnly` away from being in somebody's shipped mod.
# ---------------------------------------------------------------------------
offenders=$(grep -n -e 'maven-publish' -e 'publishing[[:space:]]*{' build.gradle || true)
if [ -n "$offenders" ]; then
    echo "$offenders" >&2
    fail "build.gradle publishes artifacts. This jar is never published; see SECURITY.md."
fi

# ---------------------------------------------------------------------------
# 4. Off unless asked.
#
# The socket may only be opened after the port system property has been read. Losing this turns a
# jar sitting in a mods folder into a listening endpoint.
# ---------------------------------------------------------------------------
if ! grep -q 'devbridge.port' src/main/java/dev/mcbridge/devbridge/DevBridge.java; then
    fail "DevBridge no longer reads the devbridge.port property: the opt-in gate is gone."
fi

# ---------------------------------------------------------------------------
# 5. No client class outside the client-only files.
#
# A dedicated server has no client classes at all, and the JVM resolves a method's types when it
# verifies the method. One `net.minecraft.client` reference in a class the server touches crashes it
# the moment something calls into that class, and the crash names a missing class rather than the
# file that mentioned it. The client-only files below are reached solely through the
# isDedicatedServer guard in ClientHandlers and are never loaded on a server.
#
# Adding a client-only class means adding it here, on purpose.
# ---------------------------------------------------------------------------
client_only="ScreenshotTaker.java ClientOptions.java InputLock.java ScreenDriver.java Sightline.java"
for f in $(find "$src" -name '*.java'); do
    case " $client_only " in
        *" $(basename "$f") "*) continue ;;
    esac
    offenders=$(grep -Hn \
        -e 'net\.minecraft\.client' \
        -e 'net\.neoforged\.neoforge\.client' \
        -e 'com\.mojang\.blaze3d' \
        -e 'org\.lwjgl' \
        "$f" | code_only)
    if [ -n "$offenders" ]; then
        echo "$offenders" >&2
        fail "client class referenced outside the client-only files ($client_only)."
    fi
done

# ---------------------------------------------------------------------------
# 6. The mod and the client agree on the protocol version.
#
# This is the check the merge was for. Before the client lived here, three verbs and two ping fields
# were added to the mod with nothing anywhere to notice that the client could not speak them. Two
# halves of one wire protocol in one repo are only worth anything if something enforces that they
# ship together.
# ---------------------------------------------------------------------------
mod_protocol=$(grep -oE 'PROTOCOL_VERSION = [0-9]+' \
    src/main/java/dev/mcbridge/devbridge/Handlers.java | grep -oE '[0-9]+' || true)
client_protocol=$(grep -oE '^PROTOCOL_VERSION = [0-9]+' \
    gamebridge/gamebridge/devbridge.py | grep -oE '[0-9]+' || true)

if [ -z "$mod_protocol" ] || [ -z "$client_protocol" ]; then
    fail "could not read PROTOCOL_VERSION from both the mod and the client."
elif [ "$mod_protocol" != "$client_protocol" ]; then
    fail "protocol drift: the mod speaks $mod_protocol, the client speaks $client_protocol."
fi

if [ "$status" -eq 0 ]; then
    echo "invariants OK: loopback-only bind, no wildcard address, not publishable, opt-in gated,"
    echo "no client classes outside $client_only."
fi

exit "$status"
