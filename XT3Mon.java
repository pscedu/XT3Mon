/* $Id$ */

import java.applet.*;
import java.awt.*;
import java.io.*;
import java.util.*;

public class XT3Mon extends Applet implements Runnable {
	private final static int SLEEPINTV = 5;

	private final static int NROWS = 1;
	private final static int NCABS = 11;
	private final static int NCAGES = 3;
	private final static int NMODS = 8;
	private final static int NNODES = 4;

	/*
	 * Login node IDs are appended to the end of the file.
	 */
	private final static String _PATH_JOBMAP =
	    "/usr/users/torque/nids_list_login";
	private final static String _PATH_PHYSMAP_PRE =
	    "/opt/tmp-harness/default/ssconfig/sys";
	private final static String _PATH_PHYSMAP_SUF = "/nodelist";
	private final static int[] logids = {1, 3, 4, 5};

	private long lastrun;
	private Thread t;
	private int width;
	private int height;
	private Node[][][][][] nodes = new Node[NROWS][NCABS][NCAGES][NMODS][NNODES];
	private LinkedList jobs;

	public void start() {
		this.t = new Thread(this);
		this.t.start();
	}

	public void stop() {
		this.t = null;
	}

	public void run() {
		Thread me = Thread.currentThread();
		while (this.t == me) {
			try {
				Thread.currentThread().sleep(SLEEPINTV);
			} catch (Exception e) {
				System.out.println(e.toString());
			}
			repaint();
		}
	}

	/*
	 * Coordinate format:
	 *	coord					cpu
	 *	============================================
	 *	c0-0c0s0				0
	 *
	 *	'C' cab# '-' row# 'c' cage# 's' slot#	node#
	 */
	public int[] parse_coord(String coord, String cpu) {
		int[] pos = new int[5];

		int p = 0, j;
		for (int i = 0; i < coord.length(); i++) {
			char c = coord.charAt(i);
			if (Character.isDigit(c)) {
				/* -1 because cpu# is not in `coord'. */
				if (p >= pos.length - 1)
					return (null);
				j = 0;
				while (Character.isDigit(coord.charAt(++j + i)))
					;
				j--;
				pos[p] = (int)Integer.getInteger(
				    coord.substring(i, j)).intValue();
				i += j - 1;
			} else {
				switch (c) {
				case 'C': case 'c':
				case 's':
					break;
				default:
					return (null);
				}
			}
		}
		if (p != pos.length - 1)
			return (null);
		pos[p] = (int)Integer.getInteger(cpu).intValue();
		/*
		 * Parsed order is (1), but we want (2):
		 *	(1) cb r cg m n
		 *	(2) r cb cg m n
		 * So swap first two elements;
		 */
		p = pos[0];
		pos[0] = pos[1];
		pos[1] = p;
		return (pos);
	}

	/*
	 * Format is:
	 *	0			1	2	3
	 *  coord		cpu	nid	type
	 *	========================
	 *	c1-0c0s0	0	0	i
	 */
	public void parse_physmap()
	  throws FileNotFoundException, IOException {
		int[] c;
		for (int i = 0; i < logids.length; i++) {
			BufferedReader r = new BufferedReader(new
			    FileReader(_PATH_PHYSMAP_PRE + i + _PATH_PHYSMAP_SUF));
			String l;
			int lineno = 0;
			while ((l = r.readLine()) != null) {
				lineno++;
				if (l.charAt(0) == '#')
					continue;
				String[] s = l.split(" ");
				if (s.length != 4 ||
				    (c = this.parse_coord(s[0], s[1])) == null) {
					System.err.println("Warning: malformed line " +
					  "(" + _PATH_PHYSMAP_PRE + i + _PATH_PHYSMAP_SUF +
					  ":" + lineno + "): " + l);
					continue;
				}
				/*         r     cb    cg    m     n */
				Node n = this.nodes[c[0]][c[1]][c[2]][c[3]][c[4]];
				n.nid = (int)Integer.getInteger(s[2]).intValue();
				switch (s[3].charAt(0)) {
				case 'n':
					n.state = Node.ST_DISABLED;
					break;
				case 'i':
					n.state = Node.ST_IO;
					break;
				case 'c':
					/* A white lie, but good enough. */
					n.state = Node.ST_FREE;
					break;
				}
			}
			r.close();
		}
	}

	public void init() {
		this.lastrun = 0;
		this.jobs = new LinkedList();
		for (int r = 0; r < NROWS; r++)
			for (int cb = 0; cb < NCABS; cb++)
				for (int cg = 0; cg < NCAGES; cg++)
					for (int m = 0; m < NMODS; m++)
						for (int n = 0; n < NNODES; n++)
							this.nodes[r][cb][cg][m][n] = new Node(-1);
		try {
			this.parse_physmap();
		} catch (Exception e) {
			System.err.println("Cannot open physical map");
			System.exit(1);
		}
		this.width = 500;
		this.height = 500;
		this.resize(this.width, this.height);
	}

	public synchronized void update(Graphics g) {
		Date now = new Date();
		if (this.lastrun + SLEEPINTV >= now.getTime())
			return;
		try {
			this.fetch();
			this.lastrun = now.getTime();
			this.paint(g);
		} catch (Exception e) {
			System.out.println(e.toString());
		}
	}

	public int[] posForId(int id) {
		int[] pos = new int[5];
		pos[0] = id / NROWS;
		id %= NROWS;
		pos[1] = id / NCABS;
		id %= NCABS;
		pos[2] = id / NCAGES;
		id %= NCAGES;
		pos[3] = id / NMODS;
		id %= NMODS;
		pos[4] = id / NNODES;
		return (pos);
	}

	public void fetch() throws Exception {
		for (int i = 0; i < logids.length; i++) {
			BufferedReader r = new BufferedReader(new
			   FileReader(_PATH_JOBMAP + i));
			String l;
			while ((l = r.readLine()) != null) {
				String[] s = l.split(" ");
			}
			r.close();
		}
	}

	public synchronized void paint(Graphics g) {
		Node node;

		g.setColor(Color.white);
		g.fillRect(0, 0, this.width, this.height);
		if (this.jobs.size() == 0)
			return;
		int x = 0, y = 0;
		int r, cb, cg, m, n, j;

		int rowheight = this.height / NROWS;
		int cgheight = rowheight / NCAGES;
		int cbwidth = this.width / NCABS;
		int modwidth = cbwidth / NMODS;
		int nodeheight = cgheight / NNODES;
		int nodewidth = modwidth;

		for (r = 0; r < NROWS; r++, y += rowheight) {
			for (cb = 0; cb < NCABS; cb++, x += cbwidth) {
				for (cg = 0; cg < NCAGES; cg++, y += cgheight) {
					for (m = 0; m < NMODS; m++, x += modwidth) {
						for (n = 0; n < NNODES; n++, y += nodeheight) {
							node = this.nodes[r][cb][cg][m][n];
							Color c = null;
							switch (node.state) {
							case Node.ST_FREE:
								c = Color.white;
								break;
							case Node.ST_USED:
								c = getColor(node.job_id);
								break;
							case Node.ST_DOWN:
								c = Color.red;
								break;
							case Node.ST_UNACC:
								c = Color.black;
								break;
							case Node.ST_IO:
								c = Color.yellow;
								break;
							case Node.ST_DISABLED:
								c = Color.gray;
								break;
							}
							g.setColor(c);
							g.fillRect(x, y, nodewidth, nodeheight);
						}
						y -= nodeheight * NNODES;
					}
					x -= modwidth * NMODS;
				}
				y -= cgheight * NCAGES;
			}
			x -= cbwidth * NCABS;
		}
	}

	public Color getColor(int j) {
		int n = this.jobs.size();
		int r, g, b;
		double bres, div = j / n;

		r = (int)Math.cos(div);
		g = (int)(Math.sin(div) * Math.sin(div));
		bres = Math.tan(div == 0.0 ? 0.1 : div);
		if (bres != 0.0)
			bres = 1 / bres;
		b = (int)bres;
		return (new Color(r, g, b));
	}
};

class Job {
	public int id;
	public LinkedList nodes;

	public Job(int id, LinkedList nodes) {
		this.id = id;
		this.nodes = nodes;
	}
};

class Node {
	public final static int ST_FREE = 1;
	public final static int ST_DOWN = 2;
	public final static int ST_DISABLED = 3;
	public final static int ST_USED = 4;
	public final static int ST_IO = 5;
	public final static int ST_UNACC = 6;

	public int nid;
	public int job_id;
	public int state;

	public Node(int id) {
		this.nid = id;
		this.state = ST_UNACC;
	}
};

/* vim:set ts=4: */
