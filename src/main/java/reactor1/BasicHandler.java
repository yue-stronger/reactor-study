package reactor1;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * 单线程基本处理器，I/O 的读写以及业务的处理均由 Reactor 线程完成
 *
 * @author chenyang.yue
 */
public class BasicHandler implements Runnable {

	private static final int MAX_IN = 1024;
	private static final int MAX_OUT = 1024;

	public SocketChannel socket;
	public SelectionKey sk;
	public ByteBuffer input = ByteBuffer.allocate(MAX_IN);
	public ByteBuffer output = ByteBuffer.allocate(MAX_OUT);

	/**
	 * 定义服务的逻辑状态
 	 */
	public static final int READING = 0, SENDING = 1, CLOSED = 2;
	public int state = READING;

	public BasicHandler(Selector sel, SocketChannel sc) throws IOException {
		socket = sc;
		// 设置非阻塞
		sc.configureBlocking(false);
		// Optionally try first read now
		// 注册通道
		sk = socket.register(sel, 0);
		// 绑定要处理的事件
		sk.interestOps(SelectionKey.OP_READ);
		// 管理事件的处理程序
		sk.attach(this);

		sel.wakeup(); // 唤醒 select() 方法
	}

	public BasicHandler(SocketChannel sc){
		socket = sc;
	}

	@Override
	public void run() {
		try {
			if (state == READING) {
				read(); // 此时通道已经准备好读取字节
			} else if (state == SENDING) {
				send(); // 此时通道已经准备好写入字节
			}
		} catch (IOException ex) {
			// 关闭连接
			try {
				sk.channel().close();
			} catch (IOException ignore) {
			}
		}
	}

	/**
	 * 从通道读取字节
	 */
	protected void read() throws IOException {
		input.clear(); // 清空接收缓冲区
		int n = socket.read(input);
		// 如果读取了完整的数据
		if (inputIsComplete(n)) {
			process();
			// 待发送的数据已经放入发送缓冲区中

			// 更改服务的逻辑状态以及要处理的事件类型
			sk.interestOps(SelectionKey.OP_WRITE);
		}
	}

	/**
	 * 缓存每次读取的内容
 	 */
	StringBuilder request = new StringBuilder();
	/**
	 * 当读取到 \r\n 时表示结束
	 * @param bytes 读取的字节数，-1 通常是连接被关闭，0 非阻塞模式可能返回
	 * @throws IOException
	 */
	protected boolean inputIsComplete(int bytes) throws IOException {
		if (bytes > 0) {
			input.flip(); // 切换成读取模式
			while (input.hasRemaining()) {
				byte ch = input.get();

				// ctrl+c 关闭连接
				if (ch == 3) {
					state = CLOSED;
					return true;
					// continue
				} else if (ch == '\r') {
				} else if (ch == '\n') {
					// 读取到了 \r\n 读取结束
					state = SENDING;
					return true;
				} else {
					request.append((char)ch);
				}
			}
		} else if (bytes == -1) {
			// -1 客户端关闭了连接
			throw new EOFException();
		} else {} // bytes == 0 继续读取
		return false;
	}

	/**
	 * 根据业务处理结果，判断如何响应
	 * @throws EOFException 用户输入 ctrl+c 主动关闭
	 */
	protected void process() throws EOFException {
		if (state == CLOSED) {
			throw new EOFException();
			// } else if (state == SENDING) {
		} else {
			// 请求内容
			String requestContent = request.toString();
			byte[] response = requestContent.getBytes(StandardCharsets.UTF_8);
			output.put(response);
		}
	}

	protected void send() throws IOException {
		int written = -1;
		output.flip();// 切换到读取模式，判断是否有数据要发送
		if (output.hasRemaining()) {
			written = socket.write(output);
		}

		// 检查连接是否处理完毕，是否断开连接
		if (outputIsComplete(written)) {
			sk.channel().close();
		} else {
			// 否则继续读取
			state = READING;
			// 把提示发到界面
			socket.write(ByteBuffer.wrap("\r\nreactor> ".getBytes()));
			sk.interestOps(SelectionKey.OP_READ);
		}

	}

	/**
	 * 当用户输入了一个空行，表示连接可以关闭了
	 */
	protected boolean outputIsComplete(int written) {
		if (written <= 0) {
			// 用户只敲了个回车， 断开连接
			return true;
		}

		// 清空旧数据，接着处理后续的请求
		output.clear();
		request.delete(0, request.length());
		return false;
	}
}

