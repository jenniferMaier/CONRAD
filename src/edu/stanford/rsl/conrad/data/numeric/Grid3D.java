/*
 * Copyright (C) 2010-2014 - Andreas Keil
 * CONRAD is developed as an Open Source project under the GNU General Public License (GPL).
 */
package edu.stanford.rsl.conrad.data.numeric;

import java.util.ArrayList;

import edu.stanford.rsl.conrad.geometry.shapes.simple.PointND;
import edu.stanford.rsl.conrad.geometry.transforms.Transform;
import edu.stanford.rsl.conrad.geometry.transforms.Transformable;
import edu.stanford.rsl.conrad.parallel.ParallelThreadExecutor;
import edu.stanford.rsl.conrad.parallel.ParallelizableRunnable;
import edu.stanford.rsl.conrad.parallel.SimpleParallelThread;
import edu.stanford.rsl.conrad.utils.CONRAD;
import edu.stanford.rsl.conrad.utils.ImageUtil;

/**
 * The three-dimensional version of a Grid.
 * 
 * @see Grid
 * 
 * @author Andreas Keil
 */
public class Grid3D extends NumericGrid implements Transformable  {

	/**
	 * The actual data buffer. This is supposed to be initialized outside of and
	 * before it is wrapped by Grid1D.
	 */
	protected ArrayList<Grid2D> buffer = null;

	/**
	 * Constructor that directly allocates the Memory
	 * @param width
	 * @param height
	 * @param depth
	 */
	public Grid3D(int width, int height, int depth){
		this(width, height, depth, true);
	}

	/**
	 * Constructor does not allocate the Memory but saves the size
	 * @param width
	 * @param height
	 * @param depth
	 * @param allocateImmediately Boolean flag for memory allocation
	 */
	public Grid3D(int width, int height, int depth, boolean allocateImmediately){
		buffer = new ArrayList<Grid2D>(depth);

		this.size = new int[] { width, height, depth};
		this.spacing = new double[3];
		this.origin = new double[3];
		for (int i = 0; i < 3; ++i) {
			assert this.size[i] > 0 : "Size values have to be greater than zero!";
		}

		if (allocateImmediately) {
			allocate();
		} else {
			for (int i=0; i < size[2]; ++i) {
				buffer.add(null);
			}		
		}
	}

	private class RotationThread extends SimpleParallelThread{
		Grid3D outputImage;
		Grid3D inputImage;
		Transform transform;
		int maxThreads;
		
		public RotationThread(int threadNum, int maxThreads, Grid3D inputImage, Grid3D outputImage, Transform transform) {
			super(threadNum);
			this.maxThreads = maxThreads;
			this.outputImage = outputImage;
			this.inputImage = inputImage;
			this.transform = transform;
		}

		@Override
		public void execute() {
			int blockSize = (int)Math.ceil(((double)outputImage.getSize()[2]) / maxThreads);
			int start = this.threadNum * blockSize;
			int end = (this.threadNum+1) * (blockSize);
			if (end > outputImage.getSize()[2]) end = outputImage.getSize()[2];
			for (int k=start; k < end; ++k) {
				for (int j=0; j < outputImage.getSize()[1]; ++j){
					for (int i=0; i < outputImage.getSize()[0]; ++i){
						PointND newPos = new PointND(outputImage.indexToPhysical(i, j, k));
						newPos.applyTransform(transform);
						double[] pos = inputImage.physicalToIndex(newPos.get(0),newPos.get(1), newPos.get(2));
						float val = InterpolationOperators.interpolateLinear(inputImage, pos[2], pos[0], pos[1]);
						outputImage.setAtIndex(i, j, k, val);
					}
				}
			}
		}
		
	}
	
	/**
	 * Allocate the memory for all slices
	 */
	public void allocate ()
	{
		for (int i=0; i < size[2]; ++i) {
			buffer.add(new Grid2D(size[0],size[1]));
			// Each subgrid has to inherit spacing and origin of parent grid
			buffer.get(i).setSpacing(this.getSpacing()[0],this.getSpacing()[1]);
			buffer.get(i).setOrigin( this.getOrigin()[0], this.getOrigin()[1]);
		}
	}

	@Override
	public void applyTransform(Transform t) {
		notifyBeforeRead();
		// determine bounds to fit the entire volume!
		PointND min = new PointND(Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE);
		PointND max = new PointND(-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE);
		for (int k=0; k < 2; ++k) {
			int indk = k * (size[2]-1);
			for (int j=0; j < 2; ++j){
				int indj = j * (size[1]-1);
				for (int i=0; i < 2; ++i){
					int indi = i * (size[0]-1);
					double [] world = this.indexToPhysical(indi, indj, indk);
					PointND newPos = new PointND(world);
					newPos.applyTransform(t);
					world = newPos.getCoordinates();
					for (int d =0; d<3; d++) {
						if (world[d] < min.get(d)) min.set(d, world[d]);
						if (world[d] > max.get(d)) max.set(d, world[d]);
					}
				}
			}
		}
		// make spacing isotropic!
		int [] newsize = new int[3];
		double spacingIso = Double.MAX_VALUE;
		for (int d =0; d<3; d++) {
			spacingIso = Double.min(spacingIso, spacing[d]);
		}
		for (int d =0; d<3; d++) {
			newsize[d] = (int) ((max.get(d)-min.get(d)) / spacingIso) + 1;
		}
		//System.out.println(min + " " + max + " " +new PointND(spacing));
		Grid3D tmp = new Grid3D(newsize[0],newsize[1],newsize[2]);
		tmp.setSpacing(spacingIso,spacingIso,spacingIso);
		PointND newOrigin = min;
		//System.out.println(newOrigin);
		tmp.setOrigin(newOrigin.getCoordinates());
		Transform inverse = t.inverse();

		int numThreads = CONRAD.getNumberOfThreads();
		ParallelizableRunnable [] runnables = new ParallelizableRunnable[numThreads];
		for (int j= 0; j<numThreads; j++) {
			runnables[j]= new RotationThread(j, numThreads, this, tmp, inverse);
		}
		ParallelThreadExecutor executor = new ParallelThreadExecutor(runnables);
		try {
			executor.execute();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		this.origin = tmp.origin;
		this.buffer = tmp.buffer;
		this.size = tmp.size;
		this.spacing = tmp.spacing;
		tmp = null;
		notifyAfterWrite();
	}

	// TODO Does this need to copy data or simply safe the reference??
	// Removed because never used and won't work when using OpenCLGrid3D
	//	public void addSlice(Grid2D slice)
	//	{
	//		buffer.add(slice);
	//		size[2]=buffer.size();
	//		notifyAfterWrite();
	//	}
	//	
	//	public void removeSlice(int idx){
	//		assert(idx >= 0 && idx < buffer.size());
	//		buffer.remove(idx);
	//		size[2]=buffer.size();
	//		notifyAfterWrite();
	//	}

	/*

	public Grid3D(float[][][] buffer, int[] boundarySize) {
		assert boundarySize.length == 3 : "A boundarySize dimension of "
				+ boundarySize.length
				+ " does not match this Grid's dimension!";
		this.buffer = buffer;
		this.size = new int[] { buffer.length - boundarySize[0] * 2,
				buffer[0].length - boundarySize[1] * 2,
				buffer[0][0].length - boundarySize[2] * 2 };
		this.spacing = new double[3];
		this.origin = new double[3];
		for (int i = 0; i < 3; ++i) {
			assert this.size[i] > 0 : "Size values have to be greater than zero!";
		}
		this.subGrids = new Grid2D[this.buffer.length];
		for (int i = 0; i < this.buffer.length; ++i)
			this.subGrids[i] = new Grid2D(this.buffer[i]);
	}

	 */
	public ArrayList<Grid2D> getBuffer() {
		notifyBeforeRead();
		return this.buffer;
	}


	public Grid3D(Grid3D input){
		this.size = input.size;
		this.spacing = input.spacing;
		this.origin = input.origin;

		this.buffer = new ArrayList<Grid2D>(size[2]);
		for (int i=0; i < size[2]; ++i) {
			buffer.add(new Grid2D(input.getSubGrid(i)));
		}
	}

	public Grid3D(Grid3D input, boolean ensureValidValues){
		this(input);
		if(ensureValidValues)
			getGridOperator().fillInvalidValues(this);
	}

	public Grid2D getSubGrid(int i) {
		notifyBeforeRead();
		if (i >= buffer.size()) return null; 
		if (i < 0) return null;
		return this.buffer.get(i);
	}

	/*
	 * Be careful when using this method. If the grid is larger than the old grid the size will be 
	 * overwritten and the origin etc may change!
	 */
	public void setSubGrid(int i, Grid2D grid) {
		if(grid.getWidth() != size[0] || grid.getHeight() != size[1]) {
			size[0] = grid.getWidth();
			size[1] = grid.getHeight();
			System.out.println("Warning. Changing grid size! Be careful while setting the 2D subgrid!");
		}
		this.buffer.set(i, grid);
		notifyAfterWrite();
	}

	public double[] indexToPhysical(double i, double j, double k) {
		return new double[] { i * this.spacing[0] + this.origin[0],
				j * this.spacing[1] + this.origin[1],
				k * this.spacing[2] + this.origin[2] };
	}

	public double[] physicalToIndex(double x, double y, double z) {
		return new double[] { (x - this.origin[0]) / this.spacing[0],
				(y - this.origin[1]) / this.spacing[1],
				(z - this.origin[2]) / this.spacing[2] };
	}

	public float getAtIndex(int i, int j, int k) {
		notifyBeforeRead();
		return this.buffer.get(k).getPixelValue(i, j);
	}

	public void setAtIndex(int i, int j, int k, float val) {
		this.buffer.get(k).putPixelValue(i, j,val);
		notifyAfterWrite();
	}

	public void addAtIndex(int i, int j, int k, float val) {
		notifyBeforeRead();
		float gridVal = getAtIndex(i, j, k); // TODO test for debug
		setAtIndex(i, j, k, gridVal + val);
		notifyAfterWrite();
	}

	public void multiplyAtIndex(int i, int j, int k, float val) {
		notifyBeforeRead();
		setAtIndex(i, j, k, getAtIndex(i, j, k) * val);
		notifyAfterWrite();
	}

	@Override
	public String toString() {
		this.notifyBeforeRead();
		String result = new String();
		result += "[";
		for (int i = 0; i < this.buffer.size(); ++i) {
			if (i != 0)
				result += ", ";
			result += this.getSubGrid(i).toString();
		}
		result += "]";
		if(debug){
			float min = this.getGridOperator().min(this);
			float max = this.getGridOperator().max(this);
			result += " " + min + ":" + max;
		}
		return result;
	}


	public void show(String title){
		ImageUtil.wrapGrid3D(this, title).show();
	}

	public void show(){
		show("Grid3D");
	}

	@Override
	public float getValue(int[] idx) {
		notifyBeforeRead();
		return this.getAtIndex(idx[0], idx[1], idx[2]);
	}

	@Override
	public NumericGrid clone() {
		notifyBeforeRead();
		return (new Grid3D(this));
	}

	@Override
	public void setSpacing(double... spacing){
		super.setSpacing(spacing);
		for (Grid2D slice : buffer){
			if (slice != null)
				slice.setSpacing(spacing[0],spacing[1]);
		}
	}

	@Override
	public void setOrigin(double... origin){
		super.setOrigin(origin);
		for (Grid2D slice : buffer){
			if (slice != null)
				slice.setOrigin(origin[0],origin[1]);
		}
	}

	@Override
	public void setValue(float val, int[] idx) {
		setAtIndex(idx[0], idx[1], idx[2], val);
		notifyAfterWrite();
	}

}


