---
description: Build, Test, and Debug the StepsCounter detailed workflow
---

This workflow provides a comprehensive set of instructions for the AI assistant to build, test, and debug the StepsCounter application.
Use this workflow to ensure code stability and catch errors early.

1. **Clean and Build**:
   - Run `./gradlew clean` to ensure a fresh build.
   - Run `./gradlew assembleDebug` to compile the application.
   - **Action**: If the build fails, analyze the error output, fix the issue in the code, and repeat this step.

2. **Run Lint Checks**:
   - Run `./gradlew lint` to perform static code analysis.
   - **Action**: Check `app/build/reports/lint-results.html` (or the console output) for errors and warnings. Fix high-priority issues.

3. **Run Unit Tests**:
   - Run `./gradlew test` to execute local unit tests.
   - **Action**: If any tests fail, investigate the cause (logic error vs test error) and fix it.

4. **Verify Android Instrumentation Tests** (Optional):
   - If an emulator or device is connected, run `./gradlew connectedAndroidTest`.
   - **Action**: Ensure all UI and integration tests pass.

5. **Deploy and Runtime Check** (If Device Connected):
   - Run `./gradlew installDebug` to install the app.
   - Launch the app: `adb shell monkey -p com.example.stepscounter -c android.intent.category.LAUNCHER 1`
   - Capture logs: `adb logcat -d *:E` (checking for errors/crashes).
   - **Action**: If a runtime exception occurs, identifying the stack trace and fixing the root cause is mandatory.

6. **Code Style and Cleanup**:
   - Check for leftover TODOs: `grep -r "TODO" app/src/main`
   - Ensure no hardcoded strings are added (use `strings.xml`).
   - Verify that new files have appropriate package declarations.

7. **Final Verification**:
   - After all fixes, run the full suite again to ensure no regressions.
