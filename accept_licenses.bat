@echo off
setlocal enabledelayedexpansion

:loop
set "input="
set /p "input="
if "!input!"=="Accept? (y/N):" (
    echo y
    goto loop
)
echo !input!
goto loop