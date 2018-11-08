## File descriptor leak when using vert.x

When using the vert.x web client, I am getting file descriptor leaks when a request fails due to a Timeout.

repo that exhibits the issue: github.com/kjstouffer/vertx-fd-issue-repro

run with `./gradlew run`
The PID of the process is logged, eg `INFO: PID 95891@keiths-mbp`  
watch file total file descriptors open with `lsof -p $PID | wc -l`  
file descriptor count should increase at about 7 per loop with the current configuration  

The file descriptors that are leaking are like these:

```
$ lsof -p $PID | grep 'IPv6' | grep 'CLOSED'
COMMAND   PID          USER   FD      TYPE             DEVICE SIZE/OFF     NODE NAME
java    96743 keithstouffer   37u     IPv6 0x53e1cfaa224b2f91      0t0      TCP *:* (CLOSED)
...
```
