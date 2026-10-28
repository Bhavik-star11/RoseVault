# Third-party components

The MIT license covers RoseVault's original source and included project-owned assets. It does not relicense dependencies or grant rights to another party's trademarks.

The Android build depends on AndroidX/Jetpack Compose, Kotlin/coroutines, the WebRTC SDK distribution, and Google Play services Nearby. Backend tooling uses the Supabase CLI. See the pinned Gradle/package manifests and each component's published license notices for its applicable terms.

Nearby requires Google Play services on supported phones; removing Firebase does not make that optional dependency an entirely open-source radio stack. WebRTC and its distribution include additional third-party notices. Preserve upstream notices when distributing binaries.

The grey launcher concepts were generated for this project with the built-in image-generation tool. The user selected the shield-and-lock concept; design/ICON_PROMPTS.md records the directions. The pink in-app vector branding is repository-native.
