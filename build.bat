@echo off
chcp 65001 >nul
setlocal EnableExtensions

rem ============================================================
rem  Сборка плагинов SmpCore и SmpOrigins.
rem
rem  Куда положить: в общую папку, где лежат папки SmpCore и SmpOrigins
rem  (собираются все проекты рядом), или внутрь одного проекта (соберётся только он).
rem
rem  Что делает:
rem    1. Подтягивает свежий код с GitHub. Если папка ещё не связана с GitHub,
rem       сначала копирует старый код в _backup, потом ставит код с GitHub.
rem       Папки run, build и .gradle не трогаются.
rem    2. Ищет JDK 25 и собирает через Gradle Wrapper — сам Gradle ставить не нужно,
rem       при первом запуске он скачается сам (около 130 МБ).
rem    3. Кладёт готовые jar в общую папку (и в plugins сервера, если указать ниже).
rem ============================================================

rem --- Настройки ---------------------------------------------

rem Папка plugins сервера: туда скопируется готовый jar (старая версия удалится). Пусто — не копировать.
rem Пример: set "SERVER_PLUGINS=C:\Server\plugins"
set "SERVER_PLUGINS="

rem 1 — перед сборкой обновить код с GitHub, 0 — собрать то, что есть в папках
set "PULL=1"

rem JDK вручную, если сам не найдёт. Пример: set "JDK=C:\Program Files\Java\jdk-25"
set "JDK="

set "SMPCORE_REPO=https://github.com/Kismeria/SMPcore.git"
set "SMPCORE_BRANCH=claude/gifted-clarke-ouqfu3"
set "SMPORIGINS_REPO=https://github.com/Kismeria/SMPorigins.git"
set "SMPORIGINS_BRANCH=main"

rem -------------------------------------------------------------

rem git может обновить этот файл прямо во время работы — запускаемся из копии во временной папке
if /i not "%~1"=="--from" (
    copy /y "%~f0" "%TEMP%\smp-build.bat" >nul
    call "%TEMP%\smp-build.bat" --from "%~dp0." & exit /b
)
for %%P in ("%~2") do set "HERE=%%~fP"
set "SINGLE="
if exist "%HERE%\build.gradle.kts" set "SINGLE=%HERE%"
if defined SINGLE (
    for %%P in ("%HERE%\..") do set "ROOT=%%~fP"
) else (
    set "ROOT=%HERE%"
)
set "OUT=%ROOT%"
set "FAILS=0"
set "BUILT=0"

call :find_java

if "%PULL%"=="1" (
    where git >nul 2>nul
    if errorlevel 1 (
        echo [!] git не найден — собираю без обновления с GitHub
        set "PULL=0"
    )
)

if defined SINGLE (
    if "%PULL%"=="1" call :update "%SINGLE%"
    call :build "%SINGLE%"
    goto :done
)

if "%PULL%"=="1" for /d %%D in ("%ROOT%\*") do if exist "%%~fD\build.gradle.kts" call :update "%%~fD"
for /d %%D in ("%ROOT%\*") do if exist "%%~fD\build.gradle.kts" call :build "%%~fD"
goto :done


rem ============================================================ JDK
:find_java
if defined JDK goto :use_jdk
for %%R in ("%USERPROFILE%\.jdks" "%ProgramFiles%\Java" "%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Zulu" "%ProgramFiles%\BellSoft" "%ProgramFiles%\Amazon Corretto" "%ProgramFiles%\OpenJDK" "%ProgramFiles%\Semeru" "%ProgramFiles%\GraalVM") do (
    if exist "%%~R" for /d %%D in ("%%~R\*-25*") do if exist "%%~fD\bin\javac.exe" set "JDK=%%~fD"
)
if defined JDK goto :use_jdk
if defined JAVA_HOME goto :have_java_home
echo [!] JDK 25 не нашёл. Если сборка упадёт — впиши путь к JDK 25 в строку set "JDK=" вверху файла
exit /b 0

:have_java_home
echo [i] JDK 25 не нашёл, беру JAVA_HOME: %JAVA_HOME%
exit /b 0

:use_jdk
if exist "%JDK%\bin\java.exe" goto :use_jdk_ok
echo [!] В %JDK% нет bin\java.exe — проверь путь JDK
exit /b 0
:use_jdk_ok
set "JAVA_HOME=%JDK%"
set "PATH=%JDK%\bin;%PATH%"
echo [i] JDK: %JDK%
exit /b 0


rem ============================================================ GitHub
:update
set "P=%~1"
set "NAME=%~nx1"
set "REPO="
set "BRANCH="
if /i "%NAME%"=="SmpCore" (
    set "REPO=%SMPCORE_REPO%"
    set "BRANCH=%SMPCORE_BRANCH%"
)
if /i "%NAME%"=="SmpOrigins" (
    set "REPO=%SMPORIGINS_REPO%"
    set "BRANCH=%SMPORIGINS_BRANCH%"
)
if not defined REPO (
    echo [i] %NAME%: репозиторий на GitHub не задан — без обновления
    exit /b 0
)
echo.
echo === %NAME%: обновляю с GitHub ===
git ls-remote --exit-code "%REPO%" "refs/heads/%BRANCH%" >nul 2>nul
if errorlevel 1 (
    echo [!] %NAME%: на GitHub пусто или нет ветки %BRANCH% — собираю то, что есть
    exit /b 0
)
pushd "%P%"
if exist ".git" goto :update_pull

set "STAMP="
for /f %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%T"
if not defined STAMP set "STAMP=old"
set "BK=%ROOT%\_backup\%NAME%-%STAMP%"
echo [i] %NAME%: первая привязка к GitHub. Старый код сохраняю в %BK%
robocopy "%P%" "%BK%" /E /XD .gradle build run .git /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
    echo [X] %NAME%: не удалось сделать копию — не трогаю папку
    popd
    exit /b 0
)
git init -q
git remote add origin "%REPO%"
git fetch -q origin "%BRANCH%"
if errorlevel 1 (
    echo [X] %NAME%: не удалось скачать код — собираю то, что есть
    rmdir /s /q ".git"
    popd
    exit /b 0
)
git checkout -q -f -B "%BRANCH%" FETCH_HEAD
git branch -q --set-upstream-to="origin/%BRANCH%" >nul 2>nul
echo [OK] %NAME%: код с GitHub на месте
popd
exit /b 0

:update_pull
git pull --ff-only origin "%BRANCH%"
if errorlevel 1 echo [!] %NAME%: git pull не прошёл ^(есть свои изменения?^) — собираю то, что есть
popd
exit /b 0


rem ============================================================ Сборка
:build
set "P=%~1"
set "NAME=%~nx1"
echo.
echo === %NAME%: сборка ===
if not exist "%P%\run\host" echo [!] %NAME%: нет папки run\host — если плагин берёт ядро сервера оттуда, сборка упадёт

set "GW="
if exist "%P%\gradlew.bat" if exist "%P%\gradle\wrapper\gradle-wrapper.jar" set "GW=%P%\gradlew.bat"
if not defined GW for /d %%D in ("%ROOT%\*") do if exist "%%~fD\gradlew.bat" if exist "%%~fD\gradle\wrapper\gradle-wrapper.jar" set "GW=%%~fD\gradlew.bat"

if defined GW (
    call "%GW%" -p "%P%" clean build
) else (
    where gradle >nul 2>nul
    if errorlevel 1 (
        echo [X] %NAME%: нет ни gradlew.bat, ни gradle. Запусти с PULL=1 — wrapper придёт с GitHub вместе с SmpCore
        set /a FAILS+=1
        exit /b 0
    )
    call gradle -p "%P%" clean build
)
if errorlevel 1 (
    echo [X] %NAME%: сборка не удалась — ошибки выше
    set /a FAILS+=1
    exit /b 0
)
for %%F in ("%P%\build\libs\*.jar") do call :deliver "%%~fF"
exit /b 0

:deliver
set "N=%~n1"
if /i "%N:~-8%"=="-sources" exit /b 0
if /i "%N:~-8%"=="-javadoc" exit /b 0
if /i "%N:~-6%"=="-plain" exit /b 0
copy /y "%~f1" "%OUT%\" >nul
echo [OK] %~nx1 -^> %OUT%
set /a BUILT+=1
if not defined SERVER_PLUGINS exit /b 0
if exist "%SERVER_PLUGINS%\" goto :deliver_server
echo [!] Папка %SERVER_PLUGINS% не найдена — в сервер не копирую
exit /b 0
:deliver_server
for /f "delims=-" %%A in ("%N%") do set "BASE=%%A"
del /q "%SERVER_PLUGINS%\%BASE%-*.jar" 2>nul
copy /y "%~f1" "%SERVER_PLUGINS%\" >nul
echo [OK] %~nx1 -^> %SERVER_PLUGINS%
exit /b 0


rem ============================================================ Итог
:done
echo.
if not "%FAILS%"=="0" (
    echo [X] Не собралось проектов: %FAILS%. Готово jar: %BUILT%
    pause
    exit /b 1
)
echo [OK] Всё собрано. Готово jar: %BUILT% — лежат в %OUT%
start "" "%OUT%"
pause
exit /b 0
