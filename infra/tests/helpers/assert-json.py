import json
import pathlib
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
for expression in sys.argv[2:]:
    if not eval(expression, {"__builtins__": {"len": len, "set": set}}, {"document": document}):
        raise AssertionError(expression)
