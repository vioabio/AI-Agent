# Migration Runbook

## Goal

Recover `D:\lx\jianlizhuanqu` into a fully runnable workspace after moving the project to a new Windows directory.

This runbook covers:

- virtual environment recovery
- editable install rebinding
- ADB and `uiautomator2` verification
- core live-flow regression
- sample-backed smoke tests

## Quick Recovery

From the workspace root:

```powershell
cd D:\lx\jianlizhuanqu
.\scripts\rebuild_venv.ps1
.\.venv\Scripts\python.exe scripts\migration_selfcheck.py
```

If you want to skip the live profile collection stage:

```powershell
.\.venv\Scripts\python.exe scripts\migration_selfcheck.py --skip-live-profile
```

## Manual Recovery Order

### 1. Verify the migrated virtual environment

```powershell
.\.venv\Scripts\python.exe --version
.\.venv\Scripts\python.exe -m pip -V
.\.venv\Scripts\python.exe -m pytest -V
```

Expected result:

- `python.exe` runs from `D:\lx\jianlizhuanqu\.venv`
- `pip` resolves inside `D:\lx\jianlizhuanqu\.venv\Lib\site-packages`

If any launcher still points at the old directory, rebuild:

```powershell
.\scripts\rebuild_venv.ps1
```

### 2. Verify the editable package path

```powershell
.\.venv\Scripts\python.exe -c "import liepin_rpa; print(liepin_rpa.__file__)"
```

Expected result:

- output points into `D:\lx\jianlizhuanqu\src\liepin_rpa`

### 3. Verify required project structure

These paths must exist:

- `config`
- `src`
- `scripts`
- `outputs`
- `artifacts`
- `logs`
- `pyproject.toml`

Historical sample XML files under `outputs\**\hierarchy.xml` should also still be present, because smoke tests rely on them.

### 4. Run sample-backed smoke tests

```powershell
.\.venv\Scripts\python.exe -m pytest
```

The current smoke suite checks:

- frozen task generation count
- page detection against saved XML samples
- result-list extraction against saved samples
- candidate-profile parsing helpers against saved samples

### 5. Verify device and automation chain

```powershell
adb devices
.\.venv\Scripts\python.exe scripts\device_diagnose.py
```

If `uiautomator2` reports a stale `UiAutomation` session, rerun after cleanup:

```powershell
adb shell killall app_process
```

The project now also performs stronger built-in cleanup before reconnecting, including:

- stopping `com.github.uiautomator`
- stopping `com.github.uiautomator.test`
- removing ADB forwards
- waiting briefly for the device-side process to settle

### 6. Run live workflow regression

Readonly search:

```powershell
.\.venv\Scripts\python.exe scripts\run_single_search_readonly.py --brand 珀莱雅 --role 达播总监 --output-dir outputs\migration_selfcheck\readonly_search
```

Result filtering:

```powershell
.\.venv\Scripts\python.exe scripts\filter_current_result_page.py --brand 珀莱雅 --role 达播总监 --output-dir outputs\migration_selfcheck\result_filter
```

Profile collection:

Precondition:
The current phone screen must already be on a candidate detail page.

```powershell
.\.venv\Scripts\python.exe scripts\collect_current_profile_resume.py --output-dir outputs\migration_selfcheck\profile
```

### 7. One-command migrated workspace validation

```powershell
.\.venv\Scripts\python.exe scripts\migration_selfcheck.py
```

What it validates:

- Python, pip, and pytest entrypoints
- editable package path
- required project structure
- ADB and `uiautomator2`
- current-page capture and detection
- readonly search
- result-page filtering
- profile collection

Artifacts are written under:

```text
outputs/migration_selfcheck/<timestamp>/
```

## Known Migration-Specific Issues

### Old editable path still active

Symptom:

- `import liepin_rpa` resolves to the old directory

Fix:

```powershell
.\scripts\rebuild_venv.ps1
```

### `pip.exe` or `pytest.exe` still points to the old `.venv`

Symptom:

- `pip -V` shows the previous workspace path

Fix:

```powershell
.\scripts\rebuild_venv.ps1
```

### Search flow interrupted by a startup popup

Symptom:

- readonly search starts from `unknown`
- popup text resembles `开启微信通知服务`

Status:

- the search executor now dismisses common interruption buttons such as `暂不开启` automatically

### Device connect succeeds once but later reconnect fails

Symptom:

- `AccessibilityServiceAlreadyRegisteredError`
- `UiAutomation not connected`

Fix order:

1. rerun the script once
2. `adb shell killall app_process`
3. rerun `scripts\device_diagnose.py`
4. if the device is still stuck, reconnect USB or reboot the phone
