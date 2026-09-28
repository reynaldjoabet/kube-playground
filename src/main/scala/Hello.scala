object Hello extends Greeting {

  def main(args: Array[String]) = println(greeting)
}

trait Greeting {
  lazy val greeting: String = "hello"
}
