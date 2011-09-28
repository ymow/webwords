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

## A request step-by-step

If you follow an incoming request to the app, here's what the app
shows you:

 - an **embedded Jetty** HTTP server receives requests to spider sites
     - http://wiki.eclipse.org/Jetty/Tutorial/Embedding_Jetty
 - requests are forwarded to **Akka HTTP**, which uses Jetty Continuations
   to keep requests from tying up threads
     - http://akka.io/docs/akka/1.2/scala/http.html
 - the web process checks for previously-spidered info in a
   **MongoDB capped collection** which acts as a cache.
   This uses the **Heroku MongoHQ addon**.
     - http://www.mongodb.org/display/DOCS/Capped+Collections
     - http://devcenter.heroku.com/articles/mongohq
 - if the spider results are not cached, the web process
   sends a spider request to an indexer process using
   the **RabbitMQ AMQP addon**
     - http://www.rabbitmq.com/getstarted.html
     - http://blog.heroku.com/archives/2011/8/31/rabbitmq_add_on_now_available_on_heroku/
 - the app talks to RabbitMQ using **Akka AMQP**
      - http://akka.io/docs/akka-modules/1.2/modules/amqp.html
 - the indexer process receives a request from AMQP and shallow-spiders
   the site using an Akka actor that encapsulates **AsyncHttpClient**
     - https://github.com/sonatype/async-http-client
 - the indexer uses Akka, **Scala parallel collections**, and **JSoup** to
   grind through the downloaded HTML taking advantage of multiple CPU cores
     - http://www.scala-lang.org/api/current/scala/collection/parallel/package.html
     - http://jsoup.org
 - the indexer stores its output back in the MongoDB cache and sends
   an AMQP message back to the web process
 - the web process loads the now-cached data from MongoDB
 - the web process unsuspends the Jetty request and writes out the results

## Build and deploy

The build for the app illustrates:

 - SBT 0.11
    - https://github.com/harrah/xsbt/wiki/
 - xsbt-start-script-plugin (useful for Heroku deployment)
    - https://github.com/typesafehub/xsbt-start-script-plugin
 - testing with ScalaTest
    - http://www.scalatest.org/

### How to run it locally

 - Run `sbt stage` to stage the app (sbt must be sbt 0.10, not 0.7)
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

## Enjoy

Learn more about the Scala and Akka stack at http://typesafe.com/ !
