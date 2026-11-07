# Migration Runbook

## Goal

Recover the migrated project directory into a fully runnable Windows workspace.

This runbook covers:

- virtual environment recovery
- editable install rebinding
- ADB and `uiautomator2` verification
- core live-flow regression
- sample-backed smoke tests

## Quick Recovery

From the workspace root:

```powershell
cd <project-root>
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

- `python.exe` runs from `<project-root>\.venv`
- `pip` resolves inside `<project-root>\.venv\Lib\site-packages`

If any launcher still points at the old directory, rebuild:

```powershell
.\scripts\rebuild_venv.ps1
```

### 2. Verify the editable package path

```powershell
.\.venv\Scripts\python.exe -c "import liepin_rpa; print(liepin_rpa.__file__)"
```

Expected result:

- output points into `<project-root>\src\liepin_rpa`

### 3. Verify required project structure

These paths must exist:

- `config`
- `src`
- `scripts`
- `outputs`
- `artifacts`
- `logs`
- `pyproject.toml`

脱敏页面样本位于 `tests\fixtures`，并随代码版本控制；不再依赖运行时 `outputs` 目录。

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

Batch keyword workflow:

```powershell
.\.venv\Scripts\python.exe scripts\run_search_workflow.py --brand 珀莱雅 --role 达播总监 --output-dir outputs\migration_selfcheck\search_workflow
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
- batch keyword workflow
- result-page filtering
- profile collection

Artifacts are written under:

```text
outputs/migration_selfcheck/<timestamp>/
```

## Known Migration-Specific Issues

### Candidate locator schema upgrade

`ensure_resume_experience_schema()` now creates the additive
`candidate_resume_source_locators` table. The first normal database-backed run performs this
upgrade automatically. Existing resume, analysis, hit, and contact rows are not rewritten.

Configure a stable non-secret label for each authorized recruiter account:

```dotenv
LIEPIN_ACCOUNT_ALIAS=recruiter_a
```

Old rows cannot be backfilled with platform IDs that were never exposed or captured; their existing
candidate fingerprint and experience hash remain usable for content-based identity matching.

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

- keyword workflow starts from `unknown`
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
