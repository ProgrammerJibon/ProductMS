@echo off
REM Product Management System + POS - compile and run without NetBeans/Maven.
REM Needs JDK 17+ on PATH and MySQL (XAMPP) running on localhost:3306.
setlocal
cd /d "%~dp0"

if not exist "lib\mysql-connector-j-8.3.0.jar" (
    echo Libraries not found in the lib folder.
    echo Expected: mysql-connector-j, flatlaf, zxing core, openpdf jars in lib\
    pause
    exit /b 1
)

echo Compiling...
if exist out rmdir /s /q out
dir /s /b src\main\java\*.java > "%TEMP%\productms_sources.txt"
javac -encoding UTF-8 -d out -cp "lib\*" @"%TEMP%\productms_sources.txt"
if errorlevel 1 (
    echo.
    echo Compilation failed.
    pause
    exit /b 1
)

echo Starting Product Management System...
java -cp "out;lib\*" com.productms.Main
if errorlevel 1 pause
endlocal
