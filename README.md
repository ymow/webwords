Web Words, an example Akka/Scala application ready to run on Heroku.

Created by Typesafe, http://typesafe.com/

## See it live!

A live demo of the Web Words app may be running at

  http://radiant-winter-3539.herokuapp.com/

If it's down, try again later, or deploy the app yourself! (See below for
instructions.)

## Overview

Unlike the usual "Hello, World" this example shows off quite a few
different features of the Akka and Heroku platforms. There's a fair
amount of code here, but of course you can zoom in and look at the
part you care about.

The "point" of the app (if you can call it that) is to do a shallow
spider of a web site and compute some information about it, namely
word counts and a list of links found on the site.

The app shows:

- how to use **Akka** to write a highly parallel application in functional
  style, as an alternative to `java.util.concurrent`
     - http://akka.io
- how to deploy it on **Heroku** including how to use addons and multiple
  processes
     - http://heroku.com

You can run the app using free price tiers from Heroku, MongoHQ, and RabbitMQ.
You can also run it locally, of course.

## Slow down: what is this stuff?

The major technologies in the app, in brief:

- [Scala][scala] is an alternative to Java for writing Java Virtual
  Machine (JVM) applications. It's pragmatic to the core, including
  interoperability with existing Java libraries, and support for both
  object-oriented and functional programming.
- [Akka][akka] implements the [actor model][actors] with Scala and Java APIs.
  It's a middleware that simplifies scaling out. [Typesafe][typesafe]
  provides a commercially-supported [Typesafe Stack][stack] which includes both
  Akka and Scala.
- [RabbitMQ][rabbitmq] implements Advanced Message Queuing Protocol
  ([AMQP][amqp]), a way to send messages between Heroku processes.
  RabbitMQ is also a division of SpringSource and VMWare offering an
  official Heroku addon, used in this application.
- [MongoDB][mongodb] is a so-called "NoSQL" data store that stores
  [Binary JSON aka BSON][bson] documents. On Heroku, one way to
  use it is with the [MongoHQ][mongohq] addon.

## Setup

If you want to understand this app and/or try running it, here's what you'll
need.

### Prerequisites in your brain

- you should be a JVM developer: you know how to create and run a
  program on the JVM in some language, probably Java. You have no trouble
  with terms such as "class," "jar," "thread," etc.
- you should know how to write a web application in some language
  and understand HTTP.
- some familiarity with Scala; recommended book: [Programming in
  Scala][scalabook]
- ability to use git and GitHub, see
   - [Linux GitHub setup](http://help.github.com/linux-set-up-git/)
   - [OS X GitHub setup](http://help.github.com/mac-set-up-git/)
   - [Windows GitHub setup](http://help.github.com/linux-set-up-git/)

### Prerequisites on your computer

These need to be installed manually:

- a working `heroku` command on your path;
  [here are the instructions][herokucommand]
- Simple Build Tool (SBT) 0.11 setup on your path;
  [here are the instructions][xsbtsetup]
- MongoDB installed and running; [here are the instructions][mongoquick]
- RabbitMQ installed and running;
  [here are the instructions][rabbitmqinstall]

Once you install those, SBT will automatically download the other
dependencies including Scala and Akka.

### Optional tools

- [Eclipse plugin][scalaide] and [sbteclipse][sbteclipse]
- [Text editor modes][toolsupport]

## Running the application

### How to run the test suite

 - Install and start up MongoDB
    - http://www.mongodb.org/display/DOCS/Quickstart
 - Install and start up RabbitMQ Server
    - http://www.rabbitmq.com/server.html
 - Clone the [webwords repository][repo], cd into the resulting directory,
   and checkout the `heroku-devcenter` tag
 - Run `sbt test` to test the app (sbt must be sbt 0.11, not 0.7)

### How to run it locally

 - Clone the [webwords repository][repo], cd into the resulting directory,
   and checkout the `heroku-devcenter` tag
 - Run `sbt stage` to stage the app (sbt must be sbt 0.11, not 0.7)
 - Install and start up MongoDB
    - http://www.mongodb.org/display/DOCS/Quickstart
 - Install and start up RabbitMQ Server
    - http://www.rabbitmq.com/server.html
 - Launch the app as specified in `Procfile` if you have Heroku tools installed
    - run `foreman start --port 8080`
 - OR launch the app manually
    - run `indexer/target/start` to run the indexer process
    - in another terminal, run `web/target/start` to run the web process
 - Now open `http://localhost:8080` in a browser

### How to run it on Heroku

In brief:

 - Install the Heroku tools; be sure `heroku` is on your path
    - see http://devcenter.heroku.com/articles/heroku-command
 - Type these commands inside the application's git clone:
    - `heroku create --stack cedar`
    - `heroku addons:add mongohq`
    - `heroku addons:add rabbitmq`
    - `git push heroku master`
    - `heroku scale web=1 indexer=1`
    - `heroku restart`
    - `heroku open`

Here's what each step does.

`heroku create --stack cedar` creates a new Heroku application, using
Heroku's latest stack called Cedar. Scala and SBT are only supported on
Cedar. You should see output like this:

    $ heroku create --stack cedar
    Creating hollow-night-3476... done, stack is cedar
    http://hollow-night-3476.herokuapp.com/ | git@heroku.com:hollow-night-3476.git
    Git remote heroku added

The application name `hollow-night-3476` is randomized and will vary.

Next, you'll want to enable addons for MongoDB and RabbitMQ. Note that the
MongoDB addon is called `mongohq` (after the company providing it), not
`mongodb`. These addons have free pricing tiers (at least as of this
writing).

    $ heroku addons:add mongohq
    -----> Adding mongohq to hollow-night-3476... done, v3 (free)

    $ heroku addons:add rabbitmq
    -----> Adding rabbitmq to hollow-night-3476... done, v4 (free)

Once you enable the addons, you can optionally type `heroku config` to see
the environment variables your application will use to access them:

    $ heroku config
    MONGOHQ_URL  => mongodb://UUUUU:PPPPP@staff.mongohq.com:NNNN/appXXXXXX
    RABBITMQ_URL => amqp://UUUUU:PPPPPPPP@VVVVVVV.heroku.srs.rabbitmq.com:NNNN/VVVVVVV

These environment variables are visible to your app when it's running on
Heroku. See [WebWordsConfig.scala][webwordsconfig] for the Scala code to
access them.

Now, you're ready to push the application to Heroku. This can take a couple
minutes, it's slower the first time since it has to download the
Internet. Type `git push heroku master` and you should see:

    $ git push heroku master
    Counting objects: 1220, done.
    Delta compression using up to 4 threads.
    Compressing objects: 100% (636/636), done.
    Writing objects: 100% (1220/1220), 164.40 KiB, done.
    Total 1220 (delta 284), reused 0 (delta 0)

    -----> Heroku receiving push
    -----> Scala app detected
    -----> Building app with sbt v0.11.0
    -----> Running: sbt clean compile stage

    ... lots of output here related to updating and compiling ...

    -----> Discovering process types
           Procfile declares types -> indexer, web
    -----> Compiled slug size is 52.4MB
    -----> Launching... done, v7
           http://hollow-night-3476.herokuapp.com deployed to Heroku

    To git@heroku.com:hollow-night-3476.git
     * [new branch]      master -> master

The application won't work yet because the indexer process is not
running. You can verify this with `heroku ps` which will show only a web
process; worker processes don't run by default.

To start up the worker process, use:

    $ heroku scale web=1 indexer=1
    Scaling web processes... done, now running 1
    Scaling indexer processes... done, now running 1

Heroku processes are defined in a [Procfile][webwordsprocfile], where the
process name `web` is special.  The `web` process runs an instance by
default and gets a `PORT` environment variable.

At this point, you may see an issue - but it happens to be an opportunity to
learn about Heroku process management!

The web process waits for the indexer to exist before it listens on its
port. But if the web process takes too long to start listening, Heroku will
kill it off. Then the web process will be shown as crashed in `heroku ps`,
like this:

    $ heroku ps
    Process       State               Command
    ------------  ------------------  ------------------------------
    web.1         crashed for 14s     web/target/start

If you didn't type `heroku scale web=1 indexer=1` quickly enough, the web
process didn't connect to its port in time and shows as crashed.

To fix this, be sure you've done `heroku scale web=1 indexer=1`, and restart everything.

    $ heroku ps
    Process       State               Command
    ------------  ------------------  ------------------------------
    indexer.1     starting for 2s     indexer/target/start
    web.1         crashed for 1m      web/target/start
    $ heroku restart
    Restarting processes... done
    $ heroku ps
    Process       State               Command
    ------------  ------------------  ------------------------------
    indexer.1     starting for 3s     indexer/target/start
    web.1         starting for 4s     web/target/start

The `heroku ps` commands are optional, but they let you see what's going
on. By the way: when restarting processes, you can also give a process name
to restart only one process, for example `heroku restart web`.

And now Web Words should be live.

View the application with `heroku open` or by typing
`http://APPNAME.herokuapp.com/` into a browser.

If anything goes wrong, you can check the application's logs with `heroku
logs`; use `heroku logs --num 10000` to get 10000 lines of logs if you need
more than the default log size.

## Enjoy

Learn more about the Scala and Akka stack at http://typesafe.com/ !

[actorref]: http://akka.io/api/akka/1.2/#akka.actor.ActorRef "ActorRef API docs"

[actors]: http://en.wikipedia.org/wiki/Actor_model "Actor model on Wikipedia"

[akka]: http://akka.io/ "Akka home page"

[amqp]: http://www.amqp.org/confluence/display/AMQP/About+AMQP "About AMQP"

[amqptutorial]: http://www.rabbitmq.com/tutorials/amqp-concepts.html "AMQP concepts tutorial"

[asynchttpclient]: https://github.com/sonatype/async-http-client
"AsyncHttpClient on GitHub"

[bson]: http://bsonspec.org/ "BSON Spec"

[cappedcollection]: http://www.mongodb.org/display/DOCS/Capped+Collections "Capped Collections"

[casbah]: https://github.com/mongodb/casbah "Casbah on GitHub"

[caseclasses]: http://www.scala-lang.org/node/107 "Case Classes intro"

[dsl]: http://www.scala-lang.org/node/1403 "DSLs - a powerful Scala feature"

[erlang]: http://en.wikipedia.org/wiki/Erlang_%28programming_language%29
"Erlang on Wikipedia"

[functional]: http://en.wikipedia.org/wiki/Functional_programming "Functional programming on Wikipedia"

[hammersmith]: https://github.com/bwmcadams/hammersmith "Hammersmith on GitHub"

[herokucommand]: http://devcenter.heroku.com/articles/heroku-command "How to install Heroku"

[imperative]: http://en.wikipedia.org/wiki/Imperative_programming "Imperative programming on Wikipedia"

[jetty]: http://www.eclipse.org/jetty/ "Jetty home page"

[jettycontinuations]: http://wiki.eclipse.org/Jetty/Feature/Continuations  "Jetty Continuations"

[jsoup]: http://jsoup.org "JSoup home page"

[mongodb]: http://www.mongodb.org/ "MongoDB home page"

[mongohq]: http://mongohq.com/ "MongoHQ"

[mongoquick]: http://www.mongodb.org/display/DOCS/Quickstart "MongoDB Quick Start"

[par]:
http://www.scala-lang.org/api/current/scala/collection/parallel/package.html "scala.collection.parallel API docs"

[pimpmylibrary]: http://www.artima.com/weblogs/viewpost.jsp?thread=179766
"Pimp my Library blog post"

[rabbitmq]: http://www.rabbitmq.com/getstarted.html "RabbitMQ Getting Started"

[rabbitmqinstall]: http://www.rabbitmq.com/server.html "RabbitMQ server install"

[rabbitmqjava]: http://www.rabbitmq.com/java-client.html "RabbitMQ Java client home page"

[repo]: https://github.com/typesafehub/webwords/tree/heroku-devcenter "webwords on GitHub"

[salat]: https://github.com/novus/salat "Salat on GitHub"

[sbtbasic]: https://github.com/harrah/xsbt/wiki/Basic-Configuration "Basic Configuration (SBT Wiki)"

[sbteclipse]: https://github.com/typesafehub/sbteclipse "SBT Eclipse integration"

[sbtfull]: https://github.com/harrah/xsbt/wiki/Full-Configuration "Full Configuration (SBT Wiki)"

[scala]: http://www.scala-lang.org/ "Scala home page"

[scalabook]:
http://www.amazon.com/Programming-Scala-Comprehensive-Step---Step/dp/0981531644
"Programming in Scala, Second Edition"

[scalacheck]: https://github.com/rickynils/scalacheck "ScalaCheck on GitHub"

[scalaide]: http://www.scala-ide.org/ "Scala Eclipse Plugin"

[scalatest]: http://www.scalatest.org/ "ScalaTest home page"

[servlet]: http://en.wikipedia.org/wiki/Java_Servlet "Java Servlet on Wikipedia"

[specs2]: http://etorreborre.github.com/specs2/ "Specs2 on GitHub"

[stack]: http://typesafe.com/stack "Typesafe Stack"

[startscript]: https://github.com/typesafehub/xsbt-start-script-plugin "xsbt-start-script-plugin on GitHub"

[toolsupport]:
http://lampsvn.epfl.ch/trac/scala/browser/scala-tool-support/trunk/src "Scala Tool Support SVN repo"

[typesafe]: http://typesafe.com/ "Typesafe"

[webplugin]: https://github.com/siasia/xsbt-web-plugin "xsbt-web-plugin on GitHub"

[webwordsabstractworkqueue]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/AbstractWorkQueueActor.scala
"AbstractWorkQueueActor.scala"

[webwordsakkaconf]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/web/src/main/resources/akka.conf
"web process akka.conf"

[webwordsamqpcheck]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/AMQPCheck.scala "AMQPCheck.scala"

[webwordsbuild]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/project/Build.scala "Build.scala"

[webwordsclientactor]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/ClientActor.scala
"ClientActor.scala"

[webwordscommonpackage]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/package.scala
"webwords-common package.scala"

[webwordsconfig]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/WebWordsConfig.scala
"WebWordsConfig.scala"

[webwordscpuboundpool]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/CPUBoundActorPool.scala "CPUBoundActorPool.scala"

[webwordsindex]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/Index.scala "Index.scala"

[webwordsindexeractor]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/indexer/src/main/scala/com/typesafe/webwords/indexer/IndexerActor.scala "IndexerActor.scala"

[webwordsindexstorageactor]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/IndexStorageActor.scala "IndexStorageActor.scala"

[webwordsindexstoragespec]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/test/scala/com/typesafe/webwords/common/IndexStorageActorSpec.scala "IndexStorageActorSpec.scala"

[webwordsioboundpool]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/IOBoundActorPool.scala "IOBoundActorPool.scala"

[webwordsprocfile]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/Procfile
"Procfile"

[webwordsspideractor]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/indexer/src/main/scala/com/typesafe/webwords/indexer/SpiderActor.scala "SpiderActor.scala"

[webwordstesthttp]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/test/scala/com/typesafe/webwords/common/TestHttpServer.scala "TestHttpServer.scala"

[webwordstobinary]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/AbstractWorkQueueActor.scala#L16
"WorkQueueMessage.toBinary"

[webwordsurlfetcher]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/URLFetcher.scala "URLFetcher.scala"

[webwordswebactors]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/web/src/main/scala/com/typesafe/webwords/web/WebActors.scala "WebActors.scala"

[webwordswebserver]: https://github.com/typesafehub/webwords/blob/heroku-devcenter/web/src/main/scala/com/typesafe/webwords/web/WebServer.scala "WebServer.scala"

[webwordswordsactor]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/web/src/main/scala/com/typesafe/webwords/web/WebActors.scala#L35 "WebServer.scala at class WordsActor"

[webwordsworkqueueclient]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/WorkQueueClientActor.scala "WorkQueueClientActor.scala"

[webwordsworkqueueworker]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/WorkQueueWorkerActor.scala
"WorkQueueWorkerActor.scala"

[xsbtsetup]: https://github.com/harrah/xsbt/wiki/Setup "SBT setup"
