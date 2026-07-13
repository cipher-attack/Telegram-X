### tg x

This is the complete source code and the build instructions for the official alternative Android client for the Telegram messenger.

### Prerequisites

* At least **5,34GB** of free disk space: **487,10MB** for source codes and around **4,85GB** for files generated after building all variants

#### macOS

* [Homebrew](https://brew.sh)
* git with LFS, wget and sed: `$ brew install git git-lfs wget gsed && git lfs install`

#### Ubuntu

* git with LFS: `# apt install git git-lfs`
* Run `$ git lfs install` for the current user, if you didn't have `git-lfs` previously installed

#### Windows

* **Telegram X** does not provide official build instructions for Windows platform. It is recommended to rely on Linux distributions instead.

### Building

1. `$ git clone --recursive --depth=1 --shallow-submodules https://github.com/TGX-Android/Telegram-X tgx` — clone **Telegram X** with submodules
2. In case you forgot the `--recursive` flag, `cd` into `tgx` directory and: `$ git submodule init && git submodule update --init --recursive --depth=1`
3. Create `keystore.properties` file outside of source tree with the following properties:<br/>`keystore.file`: absolute path to the keystore file<br/>`keystore.password`: password for the keystore<br/>`key.alias`: key alias that will be used to sign the app<br/>`key.password`: key password.<br/>**Warning**: keep this file safe and make sure nobody, except you, has access to it. For production builds one could use a separate user with home folder encryption to avoid harm from physical theft
4. `$ cd tgx`
5. Run `$ scripts/./setup.sh` and follow up the instructions
6. If you specified package name that's different from the one Telegram X uses, [setup Firebase](https://firebase.google.com/docs/android/setup) and replace `google-services.json` with the one that's suitable for the `app.id` you need
7. Now you can open the project using **[Android Studio](https://developer.android.com/studio/)** or build manually from the command line: `./gradlew assembleUniversalRelease`.

#### Available flavors

* `arm64`: **arm64-v8a** build with `minSdkVersion` set to `21` (**Lollipop**)
* `arm32`: **armeabi-v7a** build
* `x64`: **x86_64** build with `minSdkVersion` set to `21` (**Lollipop**)
* `x86`: **x86** build
* `universal`: universal build that includes native bundles for all platforms.

### Quick setup for development

If you are developing a [contribution](https://github.com/TGX-Android/Telegram-X/blob/main/docs/PULL_REQUEST_TEMPLATE.md) to the project, you may follow the simpler building steps:

1. `$ git clone --recursive https://github.com/TGX-Android/Telegram-X tgx`
2. `$ cd tgx`
3. [Obtain Telegram API credentials](https://core.telegram.org/api/obtaining_api_id)
4. Create `local.properties` file in the root project folder using any text editor:<br/><pre># Location where you have Android SDK installed
sdk.dir=YOUR_ANDROID_SDK_FOLDER
\# Telegram API credentials obtained at previous step
telegram.api_id=YOUR_TELEGRAM_API_ID
telegram.api_hash=YOUR_TELEGRAM_API_HASH</pre>
5. Run `$ scripts/./setup.sh` — this will download required Android SDK packages and build native dependencies that aren't part of project's [CMakeLists.txt](/app/jni/CMakeLists.txt)
6. Open and build project via [Android Studio](https://developer.android.com/studio) or by using one of `./gradlew assemble` commands in terminal

### License

`Telegram X` is licensed under the terms of the GNU General Public License v3.0.

For more information, see [LICENSE](/LICENSE) file.

License of components and third-party dependencies it relies on might differ, check `LICENSE` file in the corresponding folder.