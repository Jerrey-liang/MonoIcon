#pragma once
#include <cstdint>

// Invoked only after the Profile has verified the entire native entry point.
// It creates a dedicated thread; no Bundle query runs in the installer.
extern "C" void monoicon_hyos_start_geometry_bridge(uintptr_t invokeAddress, const char* profileDigest);
