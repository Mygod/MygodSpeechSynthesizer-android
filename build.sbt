import android.Keys._

android.Plugin.androidBuild

platformTarget in Android := "android-23"

name := "speech.synthesizer"

scalaVersion := "2.11.7"

javacOptions ++= Seq("-source", "1.6", "-target", "1.6")

scalacOptions ++= Seq("-target:jvm-1.6", "-Xexperimental")

shrinkResources in Android := true

resolvers += Resolver.sonatypeRepo("public")

libraryDependencies ++= Seq(
  "com.android.support" % "support-v13" % "23.1.1",
  "tk.mygod" %% "mygod-lib-android" % "1.3.7-SNAPSHOT")
