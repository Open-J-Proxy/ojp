# Knowledge in Layers: Seeing the Big Picture with OJP's Simplified Flow Diagrams

Have you ever opened an unfamiliar project, followed a method into another class, then followed that class into another module, and forgotten what you were trying to understand? The code might be perfectly reasonable. The problem is that you are meeting all its details before you have a picture of how they fit together. Someone who knows the project can explain the main idea in a conversation, while someone arriving for the first time may spend hours, or even days, trying to build that same picture from source files.

In Open J Proxy, we are using [Simplified Flow Diagrams](MAIN_FLOWS.md) to give that conversation a home in the repository. They show the main journey of an operation across the application, the JDBC driver, the OJP server, and the database. The aim is to make the big picture accessible in minutes, not to claim that every part of the system can be mastered that quickly. Once you know the route, you can choose where to stop and look more closely.

## Paint the picture before adding the details

Think about how a painter builds a picture. The first pass establishes the base: broad areas of colour and a sense of space. Another pass adds rough outlines, so you can recognise the landscape. Later passes bring in shadows, textures, and smaller details. You do not need to see every leaf to understand that you are looking at a forest. Each pass gives you a useful picture, and each new layer makes that picture richer rather than replacing it with something unrelated.

We can share technical knowledge in the same way. Let's call it **knowledge in layers**: introduce the core idea, show the main flows, then add the conditions and implementation details as they become useful. It is a plain name for progressive knowledge delivery. The important part is not dividing a large document into smaller pages; it is making each layer understandable on its own and giving the reader a clear way to reach the next one.

For OJP, the base layer is straightforward. An application uses the OJP JDBC driver, the driver communicates with the OJP server over gRPC, and the server uses database connections managed on the server side. That tells us where the work happens and why OJP sits between the application and the database. The next layer follows a particular operation through those components. Further layers explain resource lifetimes, configuration choices, unusual paths, and finally the code behind each step.

## A flow tells a story that a class name cannot

Suppose you want to understand what happens when an application requests query results. Starting with a class name tells you where some code lives, but it does not tell you the whole journey. The [executeQuery diagram](EXECUTE_QUERY_FLOW.md) starts with the application's request and follows it through checking inputs, sending the request, checking server capacity, obtaining a session with a database connection, and running the SQL. It then follows the rows back to the application and ends with the result being closed.

That is a story you can follow without knowing the project's internal vocabulary. The larger boxes tell you which component is responsible, and the numbered steps help you keep your place. The arrows make the handoffs visible, including the request to the server and the response stream back to the driver. You can step back and see the operation as a whole instead of trying to assemble it from disconnected implementation fragments.

The overview also gives later details somewhere to belong. When you learn that the server sends rows in blocks while the application reads them, you already know which part of the journey that explains. The diagram's notes clarify that later blocks arrive through the same stream, rather than through a new fetch request for each block. They also explain that closing a result closes the database cursor, not the whole OJP connection. These are important distinctions, but they are easier to absorb after the main path makes sense.

## Simple does not mean vague

Our [diagram method](MAIN_FLOWS.md#method) puts a deliberate limit on detail. Each flow has at most fifteen step rectangles, preferably fewer, grouped inside the components that perform the work. Labels use short, plain-language actions rather than class or method names. A flow states what starts it, what it assumes, and where it ends. We trace the implementation before drawing the normal successful path, rather than drawing what we hope the system does.

Those boundaries matter because a picture without them can be simple and still misleading. The [Connect flow](CONNECT_FLOW.md), for example, ends with a JDBC connection ready to send work. It does not imply that every application connection immediately gets its own dedicated database session. The notes explain that session creation happens later when needed. Likewise, the [commit and rollback flow](TRANSACTION_FLOW.md) shows transaction completion without suggesting that the connection closes or returns to the pool at that moment.

We keep essential qualifications near the diagram and put implementation names in source links below it. Optional features and failure recovery belong in notes or separate flows when adding them would bury the main story. This is not an excuse to hide complexity. It is a way to make complexity available at the right depth. A reader should be able to understand what the overview covers and recognise when a question takes them beyond it.

## Let the next question choose the next layer

Imagine a contributor asking, “Where does the server get the connection for this query?” The diagram gives them a place to start. If their next question is about waiting for capacity, the essential notes introduce admission control before a new pooled session borrows a connection. If they need to investigate the exact behaviour, the source checkpoints lead to the relevant implementation. They do not have to study every transaction mode, optional feature, or recovery path just to understand where their question belongs.

The same approach works in a conversation between contributors. Instead of beginning with a tour of classes, we can agree on the main journey and then zoom into the part that needs attention. Someone reviewing a change can ask which step it affects and whether it changes a handoff or a resource's lifetime. Someone returning to the project after a break can rebuild their mental map before reading code. The shared picture gives us a useful starting point, even when we need different amounts of detail.

This does not make the diagram a substitute for source code, tests, or deeper design documents. It gives those resources context. The goal of understanding the overview in minutes is an intention, not a measured guarantee or a promise of expertise. The practical benefit is that readers can postpone details they do not yet need without losing sight of the whole process.

## Keep the layers connected

A painting can be finished and left alone; software keeps changing. For this approach to remain useful, the simplified picture needs to stay connected to the implementation. When behaviour changes, we should check whether the main path, its assumptions, its essential notes, or its source links also need to change. A readable diagram that describes yesterday's behaviour can send a reader in the wrong direction.

The [main-flow index](MAIN_FLOWS.md) brings together connection setup, queries, updates, transaction completion, closure, and shared remote calls. It also gives distributed XA transactions their own flows, so their coordination protocol does not overwhelm the regular JDBC story. Together, these views let someone explore the project's main processes without forcing every concern into one enormous picture.

That is the heart of knowledge in layers: show the whole shape early, then deepen understanding through repeated, purposeful passes. We want a newcomer to say, “I see how this fits together; now I know what to explore,” rather than, “I have read a lot of code, but I still cannot see the system.” Like the painter, we start with a picture people can recognise and add the detail when it helps them see more.
