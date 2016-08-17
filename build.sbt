android.Plugin.androidBuild

platformTarget := "android-24"

name := "speech.synthesizer"

scalaVersion := "2.11.8"

javacOptions ++= Seq("-source", "1.6", "-target", "1.6")

scalacOptions ++= Seq("-target:jvm-1.6", "-Xexperimental")

shrinkResources := true

typedViewHolders := false

resConfigs := Seq("zh")

useSupportVectors

resolvers += Resolver.sonatypeRepo("public")

proguardVersion := "5.2.1"

proguardCache := Seq()

libraryDependencies += "tk.mygod" %% "mygod-lib-android" % "3.0.0-SNAPSHOT"
