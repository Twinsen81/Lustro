"""The exit codes of the ``lustro`` command: one for each cause, so that a script
can tell "install a debug build" from "start the app" from "my command is wrong"
without parsing the message. The CLI README, docs/AGENTS.md, and the lustro skill
have the table: change them with this file."""

OK = 0
# The app answered with an error, or the command did not get what it waited for,
# as `net wait` at its timeout and `open` when `adb forward` fails.
ERROR = 1
# The command can't run as given: a bad flag or input, an id that more than one
# item starts with, a search that the app rejects, or more than one device with
# none selected.
USAGE = 2
# adb is not installed, or it finds no device, or the device is offline or not
# authorized.
NO_DEVICE = 3
# The app of --package is not installed on the device.
NOT_INSTALLED = 4
# The installed build of --package has no Lustro runtime: a build with the -noop
# artifacts, or one from before the app added Lustro.
NO_RUNTIME = 5
# The app is not running or not in the foreground, it logged no ready line, or
# its port does not answer.
NOT_REACHABLE = 6
