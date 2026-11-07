#include "launcher_safety_policy.h"
#include <cstdlib>
#include <iostream>

using namespace monoicon::safety;
namespace {
void Check(bool condition, const char* message) {
    if (!condition) { std::cerr << "FAILED: " << message << '\n'; std::exit(1); }
}
}
int main() {
    const Identity first{"11111111-2222-3333-4444-555555555555", 100, 500};
    const Identity second{first.boot, 101, 800};
    const Identity reboot{"22222222-2222-3333-4444-555555555555", 100, 500};
    const std::string retry = "0123456789abcdef0123456789abcdef";
    State state = Start(nullptr, first, 1000, "-");
    Check(!state.blocked && !state.healthy, "first enabled process is armed");
    Check(Start(&state, first, 1500, "-").armedMs == 1000, "duplicate hooks do not reset checkpoint");
    Check(!CanMarkHealthy(state, first, "-", 30999), "checkpoint cannot complete before 30 seconds");
    Check(CanMarkHealthy(state, first, "-", 31000), "checkpoint completes after 30 seconds alive");
    Check(!CanMarkHealthy(state, second, "-", 31000), "old worker cannot mark replacement healthy");
    Check(!CanMarkHealthy(state, first, retry, 31000), "old worker cannot overwrite explicit retry");
    Check(Start(&state, second, 2000, "-").blocked, "first short-lived restart blocks");
    Check(Start(&state, second, 999999, "-").blocked, "delayed relaunch does not erase a short-lived death");
    const Identity reused{first.boot, first.pid, first.start + 1};
    Check(Start(&state, reused, 2000, "-").blocked, "reused PID is a different process");
    Check(!Start(&state, reboot, 1000, "-").blocked, "pending checkpoint from old boot is discarded");
    Check(!Start(&state, second, 2000, "-", &first).blocked, "intentional restart exempts named predecessor");
    Check(Start(&state, second, 2000, "-", &second).blocked, "unrelated restart receipt does not exempt crash");
    State expectedNext = Start(&state, second, 2000, "-", &first);
    Check(Start(&expectedNext, reused, 3000, "-", &first).blocked, "old receipt cannot exempt another crash");
    State blocked = Start(&state, second, 2000, "-");
    Check(Start(&blocked, reboot, 1000, "-").blocked, "blocked latch survives reboot");
    Check(Start(&blocked, reboot, 1000, "-", &second).blocked, "intentional restart cannot clear latch");
    Check(!Start(&blocked, reboot, 1000, retry).blocked, "explicit new retry token clears latch");
    State retried = Start(&blocked, reboot, 1000, retry);
    State retriedBlocked = Start(&retried, Identity{reboot.boot, 200, 600}, 2000, retry);
    Check(retriedBlocked.blocked && Start(&retriedBlocked, second, 3000, "-").blocked,
        "missing retry file cannot clear a latch");
    Check(Start(&retriedBlocked, second, 3000, "-").reset == retry,
        "missing retry file cannot forget the consumed token");
    state.healthy = true;
    Check(!Start(&state, second, 40000, "-").blocked, "normal long-lived restart starts another checkpoint");
    Check(!CanMarkHealthy(blocked, second, "-", 40000), "blocked process cannot become healthy");

    State decoded;
    Check(ParseState(Encode(blocked), decoded) && decoded.blocked && decoded.process == blocked.process &&
        decoded.reset == blocked.reset && decoded.armedMs == blocked.armedMs, "persisted state round trips");
    Check(!ParseState(Encode(blocked) + "extra", decoded), "trailing state fields rejected");
    Check(!ParseState("MONOICON_SAFE 1 0 x 100 500 1000 0 -", decoded), "malformed boot token rejected");
    Check(!ParseState("MONOICON_SAFE 1 0 " + first.boot + " -1 500 1000 0 -", decoded), "negative PID rejected");
    Check(!ParseState("MONOICON_SAFE 1 0 " + first.boot + " 100 500 18446744073709551616 0 -", decoded), "overflow timestamp rejected");
    Check(!ParseState("MONOICON_SAFE 1 0 " + first.boot + " 100 500 1000 2 -", decoded), "invalid boolean rejected");
    Check(!ParseState("MONOICON_SAFE 2 0 " + first.boot + " 100 500 1000 0 -", decoded), "unknown format rejected");
    Check(!ParseState("MONOICON_SAFE 1 0 " + first.boot + " 100 500 1000 0 shell;", decoded), "invalid retry token rejected");
    Identity expected;
    Check(ParseExpected(first.boot + " 100 500\n", expected) && expected == first, "expected restart receipt parses");
    Check(!ParseExpected(first.boot + " 100 500 extra", expected), "trailing receipt fields rejected");
    std::cout << "Launcher safety policy: all lifecycle and persistence checks passed\n";
}
