scalaVersion := "2.11.8"

enablePlugins(AndroidApp)
android.useSupportVectors

name := "speech.synthesizer"
version := "2.4.2"
versionCode := Some(407)

platformTarget := "android-25"

compileOrder := CompileOrder.JavaThenScala
javacOptions ++= "-source" :: "1.7" :: "-target" :: "1.7" :: Nil
scalacOptions ++= "-target:jvm-1.7" :: "-Xexperimental" :: Nil

proguardVersion := "5.3.2"
proguardCache := Seq()

shrinkResources := true
typedViewHolders := false
resConfigs := Seq("zh-rCN")

resolvers ++= Seq(Resolver.jcenterRepo, Resolver.sonatypeRepo("public"))
libraryDependencies += "be.mygod" %% "mygod-lib-android" % "4.0.4-SNAPSHOT"
