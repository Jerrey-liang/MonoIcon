#pragma once

namespace monoicon::safety {
// Call only for an enabled module, before installing any launcher hooks.
// No spawner or secondary process can arm this guard. A false result remains
// latched in this process, even after an explicit retry token is published.
bool Begin();
bool IsBlocked();
}
