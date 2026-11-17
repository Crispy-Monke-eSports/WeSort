# Frozen Meteor dependency for Minecraft 26.3

WeSort uses the **original Meteor Client 26.3-22**, Maven coordinate
`meteordevelopment:meteor-client:26.3-20261115.173242-22`. Its upstream
snapshot URL returned 404 during W-022 CI on 2026-11-17 after snapshot 23 was
published. Selecting the latest snapshot would change the tested runtime.

The local Maven repository preserves the original JAR, POM and Gradle module
metadata from the previously verified download, byte for byte. The JAR's
`fabric.mod.json` identifies upstream commit
[`474da7c33d391109a4a1feea288ea93ea696cfa9`](https://github.com/MeteorDevelopment/meteor-client/tree/474da7c33d391109a4a1feea288ea93ea696cfa9).
Its original GPL-3.0 text is included as `LICENSE_meteor-client`. The matching
complete upstream source archive, including build scripts, is
`meteor-client-474da7c-source.zip`, downloaded from GitHub's archive for that
exact commit. Upstream authors, embedded library metadata and license
notices remain unchanged. This is a dependency distribution, not WeSort code.

`meteor-client.lock.json` records the original Maven URL, version, upstream
commit and SHA-256 of every preserved file. `gradle/frozen-meteor.gradle`
verifies all files before resolving dependencies and exclusively resolves
this module from the local Maven repository. Both the actual addon and the
isolated toolchain probe use it. There is no automatic version substitution
or dependency on the availability of a developer's Gradle cache.

Meteor's bundled libraries remain listed in the original POM/module and JAR
metadata. Minecraft itself is not vendored. A future Meteor upgrade must be
an explicit version/provenance change with real 26.3 runtime, navigation,
inventory and stash regression tests.
