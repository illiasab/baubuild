# BAU BUILD

## Web portal

Web portal for the [BAU BUILD design](https://baybuild.vercel.app/): the project board, join and request rules, My Work, the project workspace, review status, and the profile score.

The backend lives in `server/bau`. The rules live in `server/bau/logic.py`. The browser only displays the state that module returns.

### Rules

- An open project joins immediately. A professor or dev lead project creates a request and does not assign tasks yet.
- A score below the project minimum, or a class project outside your classes, changes nothing.
- Urgent work can be open to every class. That does not waive a minimum score.
- Filters combine. Search matches the title, summary, department, class, and difficulty.
- Task status moves To Do → In Progress → Done, and Done reopens to To Do.
- A push runs the AI summary and leaves the commit with the dev lead. Only a commit that was sent back can be resubmitted.
- Passing the proctored exam (3 of 4) does not make you a lead. The in-person stage with Ylyas is recorded outside this portal.
- When a lead approves the last waiting commit, the bounty is added to earnings and to the score once.

### Run

```bash
cd server/bau
python3 -m unittest test_logic.py test_api.py
PORT=8080 python3 server.py
```

Open http://127.0.0.1:8080. Progress is stored in `server/bau/data/store.json`. Profile → Reset demo data restores Sam Carter’s starting board.

## Kotlin Multiplatform app

This is a Kotlin Multiplatform project targeting Android, iOS, Web, Desktop (JVM), Server.

* [/app/iosApp](./app/iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose
  Multiplatform, you need this entry point for your iOS app. This is also where you should add SwiftUI code for your
  project.

* [/app/shared](./app/shared/src) is for code that will be shared across your Compose Multiplatform applications. It
  contains several subfolders:
    - [commonMain](./app/shared/src/commonMain/kotlin) is for code that’s common for all targets.
    - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name. For
      example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
      the [iosMain](./app/shared/src/iosMain/kotlin) folder would be the right place for such calls. Similarly, if you
      want to edit the Desktop (JVM) specific part, the [jvmMain](./app/shared/src/jvmMain/kotlin)
      folder is the appropriate location.

* [/core](./core/src) is for the code that will be shared between all targets in the project. The most important
  subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you can add code to the platform-specific
  folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and
options:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Desktop app:
    - Hot reload: `./gradlew :app:desktopApp:hotRun --auto`
    - Standard run: `./gradlew :app:desktopApp:run`
- Server: `./gradlew :server:run`
- Web app:
    - Wasm target (faster, modern browsers): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
    - JS target (slower, supports older browsers): `./gradlew :app:webApp:jsBrowserDevelopmentRun`
- iOS app: open the [/app/iosApp](./app/iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :app:shared:testAndroidHostTest`
- Desktop tests: `./gradlew :app:shared:jvmTest`
- Server tests: `./gradlew :server:test`
- Web tests:
    - Wasm target: `./gradlew :app:shared:wasmJsTest`
    - JS target: `./gradlew :app:shared:jsTest`
- iOS tests: `./gradlew :app:shared:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack
channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web). If you face any issues, please report them
on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).
