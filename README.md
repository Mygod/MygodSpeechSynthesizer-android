# Mygod Speech Synthesizer

[<img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png' height='80'/>](https://play.google.com/store/apps/details?id=tk.mygod.speech.synthesizer&utm_source=global_co&utm_medium=prtnr&utm_content=Mar2515&utm_campaign=PartBadge&pcampaignid=MKT-Other-global-all-co-prtnr-py-PartBadge-Mar2515-1)

[![Build Status](https://api.travis-ci.org/Mygod/MygodSpeechSynthesizer-android.svg)](https://travis-ci.org/Mygod/MygodSpeechSynthesizer-android)

Mygod Speech Synthesizer for Android.

## Dependencies

* Android Support Repository
* SBT

## Building

First, create a `local.properties` following [this guide](https://github.com/pfn/android-sdk-plugin#usage). Then:

    sbt clean android:packageRelease
