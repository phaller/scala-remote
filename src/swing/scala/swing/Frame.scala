package scala.swing

import javax.swing.JFrame
import event._

class Frame(val peer: JFrame) extends UIElement with RootPanel with Showable.Swing with Publisher {
  def this() = this(new JFrame)
  def title: String = peer.getTitle
  def title_=(s: String) = peer.setTitle(s)
  content = new Component {}

  override def content_=(c: Component) {
    super.content_=(c)
    peer.pack() // pack also validates, which is generally required after an add
  }
  def defaultButton: PushButton = Component.wrapperFor(peer.getRootPane.getDefaultButton)
  def defaultButton_=(b: PushButton) { peer.getRootPane.setDefaultButton(b.peer) }
  def pack(): this.type = { peer.pack(); this }
  
  def menuBar: MenuBar = Component.wrapperFor(peer.getJMenuBar)
  def menuBar_=(m: MenuBar) = peer.setJMenuBar(m.peer)
  
  peer.addWindowListener(new java.awt.event.WindowListener {
    def windowActivated(e: java.awt.event.WindowEvent) { publish(WindowActivated(Frame.this)) }
    def windowClosed(e: java.awt.event.WindowEvent) { publish(WindowClosed(Frame.this)) }
    def windowClosing(e: java.awt.event.WindowEvent) { publish(WindowClosing(Frame.this)) }
    def windowDeactivated(e: java.awt.event.WindowEvent) { publish(WindowDeactivated(Frame.this)) }
    def windowDeiconified(e: java.awt.event.WindowEvent) { publish(WindowDeiconified(Frame.this)) }
    def windowIconified(e: java.awt.event.WindowEvent) { publish(WindowIconified(Frame.this)) }
    def windowOpened(e: java.awt.event.WindowEvent) { publish(WindowOpened(Frame.this)) }
  })
}
