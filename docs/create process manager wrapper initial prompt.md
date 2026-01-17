I would like you to create @docs/process-process-manager-wrapper-spec.md that will explore creating required components that support easy migragion of existing code to managed
process with following features: new process manager will re-use existing database model as much as possible (new fields can be added), process manage will support two key
operations (1) execute - will be used to start the process (passing input state) or resume process from TSQ after failure or resume process after wait for receiving async
response (2) processAsyncResponse - for example in /Users/igormusic/code/jrcmd/src/test/java/com/ivamare/commandbus/e2e/payment/PaymentProcessManager.java we can receive async
responses to manual risk approval or L1 - L4 responses that will update process state and support resuming process via execute. This is inspired with the approach used by
temporal.io with deterministic process re-execution where process remembers the state from previos executions and skips through steps already executed (explore this concept on
the internet). Idea is that overriden abstract execute contains process steps defined as a single function that reads as a sequential code where each step is wrapped in lambda
that treats step as a command where we will need to record both request (passed to the lambda) and response (returned from the lambda) which will allow checking if the step
already successfully executed (status of the command is COMPLETED , lambda will simply return recorded response). With regards to processAsyncResponse code in execute can
simply do wait(some logical condition based on typed process state) where processAsyncResponse for example can update state.L1Received = true and then wait(state.L1Received) if
the expression is false process just stores its state in DB and exits (for example might throw exception that is handled by code invoking process.execute. From elaboration of
design point of view we can develop alternate implementation of /Users/igormusic/code/jrcmd/src/test/java/com/ivamare/commandbus/e2e/payment where execute function has sequence
of steps like update payment status to processing then book risk wait for response book fx if required submit payment and wait for response. Getting this design done right
will require mutiple iterations over spec so you will be asking clarifying questions until I say create draft. Once you created a draft I will provide feedback and you will
keep asking clarifying questions until I say update draft.  