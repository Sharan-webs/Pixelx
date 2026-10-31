# PIXEL•X

Image to MP4 on your phone. **Groq = brain, PIXEL•X engine = renderer, pixels = building blocks, frames = video.**

* Image is divided into numbered pixel blocks (default 8x8, auto-raised for big images). Each block keeps its own real pixels.
* Groq (vision model) only returns a JSON animation plan. The plan is validated; nothing from the AI is ever executed.
* Our own Kotlin engine renders every frame (translate, rotate, scale, zoom, fade, blur, colour, wave, particles, shake, distortion) and encodes H.264 MP4 frame by frame with MediaCodec. No video API is used.
* Invisible evidence mark is embedded in every video (pixel layer + file tag). Settings > Verify video evidence reads it back.
* 5 credits per device per 24 h (device ID based, no Gmail / phone).

## Build on GitHub (no Android Studio needed)

1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Optional secrets (Settings > Secrets and variables > Actions):
   * `GROQ_API_KEY` - default Groq key baked into the APK (users can override it in the app). Note: a key inside an APK can be extracted.
   * `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` - your own release keystore. Without them the APK is signed with the debug key (still installable).
3. Go to **Actions > Build PIXEL-X APK > Run workflow**. Download the APK from the run's **Artifacts**.
4. Tag a commit `v1.0.0` to also publish the APK as a GitHub Release.

## Where to change things

* Groq model: `app/build.gradle.kts` (`GROQ_MODEL`) or in the app: Settings > API change.
* System prompt for Groq: `Groq.kt` (`SYSTEM_PROMPT`) - written once, used for every request.
* Max video length / credits: `DetailsScreen` slider (Screens2.kt) and `Credits.MAX` (Store.kt).
* Watermark strength: `Evidence.STRENGTH`. Block size: `Generator.BLOCK`. Max resolution: `ImageIO.MAX_LONG_SIDE`.
* Font: Poppins (SIL Open Font License) in `res/font`.
