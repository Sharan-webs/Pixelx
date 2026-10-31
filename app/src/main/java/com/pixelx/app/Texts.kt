package com.pixelx.app

object Texts {
    const val OWNER_IG = "@sharan.in__"

    val termsIntro = "Please read these terms carefully before you use PIXEL•X. They explain what you may do with the app and with the videos it creates."

    /** Title to body. */
    val terms = listOf(
        "1. About PIXEL•X" to
            "PIXEL•X is an image-to-video app owned and operated by Sharan (Instagram: $OWNER_IG). It uses its own pixel-processing engine to turn one still image into an MP4 video.",
        "2. Invisible evidence mark" to
            "Every video created by PIXEL•X carries an invisible evidence mark. You cannot see it, but the PIXEL•X engine can detect it. The mark shows that the video was created with PIXEL•X, together with the device and the time of creation. You must not remove, hide, alter or try to defeat this mark.",
        "3. Financial, commercial and paid work" to
            "Before you use any PIXEL•X video for financial gain, you must ask for permission first. This includes advertising, paid client work, monetised channels, sponsored posts, resale, and any other business use. Send your request on Instagram to $OWNER_IG and wait for a reply. Using a video commercially without permission breaks these terms, and the evidence mark may be used to prove that the video came from PIXEL•X.",
        "4. Credits and your device" to
            "No Gmail, e-mail address or phone number is required. Credits are linked directly to your mobile device. Each device receives 5 credits every 24 hours, and one video costs 1 credit. Trying to bypass, reset or share credits is not allowed.",
        "5. Your images and descriptions" to
            "Only upload images that you own or have permission to use. Do not create content that is illegal, hateful or sexual, that involves harm to children, or that is meant to harass, deceive or mislead others. You are responsible for what you create.",
        "6. How your data is used" to
            "To plan the animation, your text description and a reduced-size copy of your image are sent to Groq, a third-party AI service. The video itself is rendered on your device. Your videos and project history stay on your device until you delete them.",
        "7. Honest limits" to
            "PIXEL•X animates what already exists in your image. It does not invent new objects or characters, and results depend on your image, your description and your device.",
        "8. No warranty" to
            "PIXEL•X is provided \"as is\", without any guarantee. The owner is not responsible for lost files, failed renders or any loss that comes from using the app.",
        "9. Changes" to
            "These terms may be updated from time to time. Continuing to use the app means that you accept the updated terms."
    )

    const val termsOutro = "By pressing Accept, you confirm that you have read these terms and that you agree to them."

    const val aboutTitle = "About this app"
    const val aboutBody =
        "PIXEL•X turns one still image into a real MP4 video, with no GPU farm and no paid video-generation service.\n\n" +
        "Your picture is divided into thousands of tiny blocks, and every block keeps its own real pixels. An AI planner reads your idea and writes a precise animation script. Our own engine, running on your phone, then moves, bends, fades and recolours those blocks frame by frame and encodes the result into a video."

    val aboutPoints = listOf(
        "Your idea" to "Describe the motion in your own words.",
        "AI planner" to "Groq decides what should move, when and how.",
        "PIXEL•X engine" to "Our renderer builds every frame from your pixels.",
        "MP4 video" to "Up to 25 seconds, ready to download."
    )

    const val aboutHonest =
        "PIXEL•X is a camera-and-motion engine. It animates what is already in your photo; it does not invent new things."

    val examples = listOf(
        "Slow cinematic zoom into the center with a soft camera drift.",
        "Make the water ripple while the clouds drift to the left.",
        "Create a particle explosion in the last few seconds.",
        "Add floating golden particles and a warm color grade."
    )
}
