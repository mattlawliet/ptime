@ECHO OFF
SET DIRNAME=%~dp0
SET APP_HOME=%DIRNAME%
SET CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

IF NOT "%JAVA_HOME%"=="" SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
IF "%JAVA_HOME%"=="" SET JAVA_EXE=java.exe

"%JAVA_EXE%" -Xmx64m -Xms64m -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
