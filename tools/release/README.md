# Release Tool

`release_tool.py` contains the helpers used by the [`release`](../../.github/workflows/release.yml)
GitHub Actions workflow to read and validate Android APK and iOS bundle version info, verify APK
signatures, check that Android and iOS versions match and advance relative to existing GitHub
releases, and render unified release notes. It depends only on the Python standard library;
`requirements.txt` lists development tools only.

- [Creating the Python Virtualenv](#creating-the-python-virtualenv)
- [Running Unit Tests](#running-unit-tests)
- [Running the Code Formatter](#running-the-code-formatter)
- [Running Type Checking](#running-type-checking)
- [Running the Linter](#running-the-linter)

## Creating the Python Virtualenv

```bash
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

## Running Unit Tests

```bash
python3 -m unittest discover -s . -p "test_*.py"
```

## Running the Code Formatter

Format files in-place:

```bash
ruff format
```

Check formatting without writing:

```bash
ruff format --check
```

Download and apply patch from a failed GitHub Actions workflow run:

```bash
gh run download <run-id> -n format-patch
git apply format.patch
```

## Running Type Checking

```bash
pyright
```

## Running the Linter

Check for lint errors:

```bash
ruff check
```

Automatically fix errors where possible:

```bash
ruff check --fix
```

Download and apply patch from a failed GitHub Actions workflow run:

```bash
gh run download <run-id> -n lint-patch
git apply lint.patch
```
