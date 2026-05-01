### Building JVM bindings

* Prepare the system
  * `macOs` Install Xcode Command Line Tools
  * `Linux` Install these tools:
    ```
    sudo apt-get install ninja-build fontconfig libfontconfig1-dev libglu1-mesa-dev libxrandr-dev libdbus-1-dev zip multistrap libx11-dev
    ```
  * `Windows`
    1. Download [Visual Studio Build Tools 2022](https://aka.ms/vs/17/release/vs_buildtools.exe).
    2. During the installation, select "Desktop development with C++"
    3. Add an environment variable `SKIKO_VSBT_PATH=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools`
       ```
       Control Panel|All Control Panel Items|System|Advanced system settings|Environment variables
       ```
       or by running `cmd` as administrator:
       ```
       setx /M SKIKO_VSBT_PATH "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools"
       ```
    4. Skiko is built using Clang-cl. Clang-cl is a part of LLVM and can be downloaded from the [LLVM project's website](https://releases.llvm.org/). Please also make sure that Clang-cl.exe is available in %PATH%.

* Install Emscripten
* Set `JAVA_HOME` to location of JDK, at least version 11
* `./gradlew :skiko:publishToMavenLocal` will build the artifact and publish it to local Maven repo

To build with debug symbols and debug Skia build use `-Pskiko.debug=true` Gradle argument.

#### JBR Skia interop PoC

The experimental JBR interop path must read `JBRSkia.ABI_ID` and `BUILD_ID`
reflectively before acquiring `JBR.getJBRSkia()`. Current local PoC command ABI
is 84. ABI 80 filled the low signed 64-bit capability mask (`-1L`) with
`COMMAND_SAVE_LAYER_BLEND_COLOR_FILTER_REF`, allowing Skiko/CMP command streams
to combine direct layer blend modes with typed color-filter descriptor handles
without passing raw Skia pointers across the runtime boundary. ABI 81 adds
`getCommandCapabilities64High()` as a second capability word. ABI 82 consumes
the first high-word bit for `COMMAND_SAVE_LAYER_IMAGE_FILTER_REF`, currently used
by graphics-layer blur image-filter descriptors. ABI 83 consumes the second
high-word bit for graphics-layer offset image-filter descriptors. ABI 84 consumes
the third high-word bit for nested/child image-filter descriptors.

#### Working with Skia sources

Gradle build downloads the necessary version of Skia by default.
However, if downloaded sources are modified, changes are discarded (Gradle
re-evaluates tasks, when outputs are changed).
To use custom version of the dependencies, specify `SKIA_DIR` environment variable.

#### Running UI tests
Add `-Dskiko.test.ui.enabled=true` to enable UI tests (integration tests, which run in the native window). Each UI test will be run on every available Graphics API of the current target.

For example, if we want to include UI tests when we test JVM target, call this:
```
./gradlew :skiko:awtTest -Dskiko.test.ui.enabled=true
```
Don't run any background tasks, click mouse, or press keys during the tests. Otherwise, they probably fail.

#### Run samples
- First follow the instruction here: [Building JVM bindings](#building-jvm-bindings)
- `./gradlew :SkiaAwtSample:run`
