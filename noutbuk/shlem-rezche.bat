@echo off
chcp 65001 >nul
rem Шлем: резче (VR-игры) + ADB по Wi-Fi. Положить в папку platform-tools (рядом с adb.exe), запускать двойным кликом.
rem Шлем — кабелем прямо в ноутбук. Всё сбрасывается перезагрузкой шлема, кроме убийцы фантомных процессов.
setlocal
set ADB=%~dp0adb.exe
if not exist "%ADB%" set ADB=%USERPROFILE%\Downloads\platform-tools\adb.exe
if not exist "%ADB%" (
  echo Не нашла adb.exe. Положи этот файл в папку platform-tools, рядом с adb.exe.
  pause & exit /b 1
)
echo.
echo  1 — Резче (суперсэмплинг 1.5x, без размытия по краям)
echo  2 — Как было
echo.
set /p V=Выбери 1 или 2 и нажми Enter: 
if "%V%"=="2" (set W=1680& set H=1760& set F=-1& set FD=1) else (set W=2496& set H=2600& set F=0& set FD=0)

echo.
echo Жду шлем... (если в шлеме окно «Разрешить отладку» — нажми «Разрешить», галочка «Всегда»)
"%ADB%" wait-for-usb-device
"%ADB%" shell setprop debug.oculus.textureWidth %W%
"%ADB%" shell setprop debug.oculus.textureHeight %H%
"%ADB%" shell setprop debug.oculus.foveation.level %F%
"%ADB%" shell setprop debug.oculus.foveation.dynamic %FD%
"%ADB%" shell settings put global settings_enable_monitor_phantom_procs false

echo.
echo Проверка:
for /f %%a in ('"%ADB%" shell getprop debug.oculus.textureWidth') do echo   ширина кадра: %%a
for /f %%a in ('"%ADB%" shell getprop debug.oculus.textureHeight') do echo   высота кадра: %%a
for /f %%a in ('"%ADB%" shell getprop debug.oculus.foveation.level') do echo   размытие краёв: %%a  (0 — выкл)
for /f %%a in ('"%ADB%" shell settings get global settings_enable_monitor_phantom_procs') do echo   убийца процессов: %%a  (false — выкл)

"%ADB%" tcpip 5555 >nul
echo.
echo Готово. ADB по Wi-Fi открыт до перезагрузки шлема — кабель можно вынуть.
echo Действует на VR-игры при СЛЕДУЮЩЕМ запуске игры. На окно HDMI Link не влияет
echo (его картинку правит Плюс: karta kartinka monitor / noch / sochnee).
echo После перезагрузки шлема — запустить этот файл снова.
echo.
pause
