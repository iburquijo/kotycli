@echo off
rem Lanzador para Windows. Espera el jar al lado de este script o en KOTYCLI_JAR.
setlocal
if "%KOTYCLI_JAR%"=="" (set "JAR=%~dp0kotycli.jar") else (set "JAR=%KOTYCLI_JAR%")
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstdin.encoding=UTF-8 -jar "%JAR%" %*
