#pragma once
#include <cstdint>

namespace monoicon::profile { class Runtime; struct Profile; }

// Invoked only after the Profile has verified the entire native entry point.
// It creates a dedicated thread; no Bundle query runs in the installer.
extern "C" void monoicon_hyos_start_geometry_bridge(monoicon::profile::Runtime* runtime,
    const monoicon::profile::Profile* profile, const char* profileDigest);
