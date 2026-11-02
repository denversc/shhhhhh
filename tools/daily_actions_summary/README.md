# Creating the Python Virtualenv

```bash
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

# Running Unit Tests

```bash
python3 -m unittest discover -s . -p "test_*.py"
```

# Running the Code Formatter

Format files in-place:

```bash
ruff format
```

Check formatting without writing:

```bash
ruff format --check
```

# Running Type Checking

```bash
pyright
```

# Running the Linter

Check for lint errors:

```bash
ruff check
```

Automatically fix errors where possible:

```bash
ruff check --fix
```
