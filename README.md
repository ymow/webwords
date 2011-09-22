An example Akka/Scala application ready to run on Heroku.

Created by Typesafe, http://typesafe.com/

Unlike the usual "Hello, World" this example shows off quite a few
different features of the Akka and Heroku platforms. There's a fair
amount of code here, but of course you can zoom in and look at the
part you care about.

The "point" of the app (if you can call it that) is to do a shallow
spider of a web site and compute some information about it, namely
word counts and a list of links found on the site.

If you follow an incoming request to the app, here's what the example
shows you:

 - how to use **Akka** to write a highly parallel application in functional
   style, as an alternative to `java.util.concurrent`
     http://akka.io
 - an **embedded Jetty** HTTP server receives requests to spider sites
     http://wiki.eclipse.org/Jetty/Tutorial/Embedding_Jetty
 - requests are forwarded to **Akka HTTP**, which uses Jetty Continuations
   to keep requests from tying up threads
     http://akka.io/docs/akka/1.2/scala/http.html
 - the web process checks for previously-spidered info in a
   **MongoDB capped collection** which acts as a cache
     http://www.mongodb.org/display/DOCS/Capped+Collections
   This uses the **Heroku MongoHQ addon**:
     http://devcenter.heroku.com/articles/mongohq
 - if the spider results are not cached, the web process
   sends a spider request to an indexer process using
   the **RabbitMQ AMQP addon**
     http://www.rabbitmq.com/getstarted.html
     http://blog.heroku.com/archives/2011/8/31/rabbitmq_add_on_now_available_on_heroku/
 - the app talks to RabbitMQ using **Akka AMQP**
      http://akka.io/docs/akka-modules/1.2/modules/amqp.html
 - the indexer process receives a request from AMQP and shallow-spiders
   the site using an Akka actor that encapsulates **AsyncHttpClient**
     https://github.com/sonatype/async-http-client
 - the indexer uses Akka, **Scala parallel collections**, and **JSoup** to
   grind through the downloaded HTML taking advantage of multiple CPU cores
     http://www.scala-lang.org/api/current/scala/collection/parallel/package.html
     http://jsoup.org
 - the indexer stores its output back in the MongoDB cache and sends
   an AMQP message back to the web process
 - the web process loads the now-cached data from MongoDB
 - the web process unsuspends the Jetty request and writes out the results

Learn more about the Scala and Akka stack at http://typesafe.com/ !
