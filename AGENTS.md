# MonoPlayer Project Guidelines

## Pull Request Workflow
- **Never commit or push changes directly to `main`.**
- For every code, DSP, UI, configuration, or documentation change:
  1. Create a descriptive working branch from `main` (e.g. `feat/<topic>`, `fix/<topic>`, `chore/<topic>`).
  2. Implement the change and verify all relevant tests pass.
  3. Commit to the branch, push to `origin`, and open a Pull Request against `main` using `gh pr create` so every change is recorded cleanly in GitHub PR history.
  4. Unless the user asks to pause for manual review, automatically merge the verified PR into `main` (`gh pr merge --merge --delete-branch`) and update local `main` (`git checkout main && git pull`).

## Native Rust & Android Build Verification
- When modifying `rust/monoplayer_core/`:
  - Run `cargo test` inside `rust/monoplayer_core/`.
  - Rebuild release libraries for the host (`cargo build --release`) and Android ABIs (`aarch64-linux-android`, `x86_64-linux-android`), updating `app/src/main/jniLibs/arm64-v8a/libmonoplayer_core.so` and `app/src/main/jniLibs/x86_64/libmonoplayer_core.so`.
- Before opening or updating any PR with code changes, run `./gradlew testDebugUnitTest assembleDebug` and include observed test counts in the PR description.
