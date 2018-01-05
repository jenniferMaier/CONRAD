/*
 * Copyright (C) 2010-2014 Mathias Unberath
 * CONRAD is developed as an Open Source project under the GNU General Public License (GPL).
*/
package edu.stanford.rsl.apps.activeshapemodel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.StringTokenizer;

import edu.stanford.rsl.conrad.geometry.shapes.activeshapemodels.GPA;
import edu.stanford.rsl.conrad.geometry.shapes.activeshapemodels.KPCA;
import edu.stanford.rsl.conrad.geometry.shapes.activeshapemodels.PCA;
import edu.stanford.rsl.conrad.geometry.shapes.activeshapemodels.kernels.GaussianKernel;
import edu.stanford.rsl.conrad.geometry.shapes.mesh.DataMatrix;
import edu.stanford.rsl.conrad.geometry.shapes.mesh.Mesh;
import edu.stanford.rsl.conrad.io.ParseInfoFile;
import edu.stanford.rsl.conrad.numerics.SimpleMatrix;

public class CreateARFF {
	/**
	 * Lists all the heart components available for modeling.
	 * @author Mathias Unberath
	 *
	 */
	public enum heartComponents{
		//MYOCARDIUM("myocardium", 4660, 9316),
		LEFT_VENTRICLE("leftVentricle", 4038, 8072);
		
		private String filename;
		private int numVertices;
		private int numTriangles;
		
		private heartComponents(String fn, int numVertices, int numTriangles){
			this.filename = fn;
			this.numVertices = numVertices;
			this.numTriangles = numTriangles;
		}
		public String getName(){
			return this.filename;
		}
		public int getNumVertices(){
			return this.numVertices;
		}
		public int getNumTriangles(){
			return this.numTriangles;
		}
	}
	
	
	/**
	 * Path to model data, i.e. the meshes stored in folders corresponding to their heart phase.
	 * The naming convention needs to follow:
	 * .../study_id/.../phase_#/meshname.vtk
	 */
	public static final File DATA_PATH = new File("E:\\_uni_\\Masterthesis\\Data\\");
	/**
	 * Path to the folder, where the heart model PCA files are stored.
	 */
	public static final String HEART_MODEL_BASE = "C:\\research\\data\\Test\\WEKA\\";
	/**
	 * Number of phases obtained in the dynamic CT scan.
	 */
	public static final int numPhases = 10;
	/**
	 * Number of components in the whole heart model.
	 */
	private static final int numModelComponents = 2;
	/**
	 * The vertex dimension of the model's vertices.
	 */
	public static final int vertexDimension = 3;
	/**
	 * Variation threshold.
	 */
	static double variationTh = 0.9;
	/**
	 * Keyword indicating folders containing phases.
	 */
	private static final String PHASE_KEY = "phase_";
	/**
	 * Keyword indicating folders containing phase-folders that have already been segmented and contain meshes.
	 */
	private static final String ANALYSIS_KEY = "analysis";
	
	private static int[] vertexOffs = new int[heartComponents.values().length];
	private static int totalVertices;
	private static int[] triangleOffs = new int[heartComponents.values().length];
	private static int totalTriangles;
	private static int[] principalComponents = new int[numPhases];
	
	@SuppressWarnings("unused")
	private static double radialKernel = 5;
	private static int numComponents = 2;
	//==========================================================================================
	// METHODS
	//==========================================================================================

	public static void main(String[] args) throws Exception{
		/*
		radialKernel = UserUtil.queryDouble("Specify sigma for Gaussian kernel:", 5.0);
		numComponents = UserUtil.queryInt("Specify number of principal components for projetion:", 3);
		variationTh = UserUtil.queryDouble("Select variance threshold for principal component dimensionality reduction:", 0.9);
		*/
		// initialize offset array for easier use later on
		int c = 0;
		totalVertices = 0;
		totalTriangles = 0;
		for(heartComponents hc : heartComponents.values()){
			vertexOffs[c] = totalVertices;
			triangleOffs[c] = totalTriangles;			
			totalVertices += hc.getNumVertices();
			totalTriangles += hc.getNumTriangles();
			c++;
		}
		
		// get all valid folders
		ArrayList<String> folders = getValidFolders();
		// run GPA and PCA
		ArrayList< double[] > scores = performPCA(folders);
		
		String pFn = HEART_MODEL_BASE + "CCmExampleScores.ccs";
		writeScores(pFn, folders, scores);
		writeARFF(folders,scores);		
	}
	
	
	/**
	 * This method performs GPA and PCA on all heart model components. It uses data from all folders passed to it via the folders list.
	 * @param folders The folders being used for input.
	 */
	private static ArrayList< double[] > performPCA(ArrayList<String> folders){
		// output folder will be named: CardiacModel
		//String outFolder = HEART_MODEL_BASE + "\\" + "CardiacModel\\";
		new File(HEART_MODEL_BASE).mkdirs();
					
		// create ONE mesh object at each phase for each training set and perform GPA and PCA on ALL heart components at the same time
		for(int i = 0; i < numPhases; i++){
			GPA populationGPA = new GPA(folders.size());
			for(int j = 0; j < folders.size(); j++){
				int componentCount = 0;
				SimpleMatrix currentVert = new SimpleMatrix(totalVertices,3);
				if(j == 0){
					SimpleMatrix currentTriangles = new SimpleMatrix(totalTriangles,3);
					for(heartComponents hc : heartComponents.values()){
						String currentComponent = "wrp_" + hc.getName() + ".vtk";
						String currentFile = folders.get(j) + "\\" + PHASE_KEY + i + "\\" + currentComponent;
						Mesh currentMesh = new Mesh(currentFile);
						currentVert.setSubMatrixValue(vertexOffs[componentCount], 0, currentMesh.getPoints());
						currentTriangles.setSubMatrixValue(triangleOffs[componentCount], 0, currentMesh.getConnectivity());
						componentCount++;
					}
					populationGPA.setConnectivity(currentTriangles);
				}else{
					for(heartComponents hc : heartComponents.values()){
						String currentComponent = "wrp_" + hc.getName() + ".vtk";
						String currentFile = folders.get(j) + "\\" + PHASE_KEY + i + "\\" + currentComponent;
						Mesh currentMesh = new Mesh(currentFile);
						currentVert.setSubMatrixValue(vertexOffs[componentCount], 0, currentMesh.getPoints());
						componentCount++;
					}
				}
				populationGPA.addElement(j, currentVert);
				
			}
			populationGPA.runGPA();
			
			// create PCA file for each phase
			KPCA kPCA = new KPCA(new DataMatrix(populationGPA));
			
			//kPCA.setKernel( new RadialKernel(900) );
			//kPCA.setKernel( new PolynomialKernel(1,1,0) );
			kPCA.setKernel( new GaussianKernel(25) );
			kPCA.numProjections = numComponents;
			
			kPCA.run();
			principalComponents[i] = kPCA.numProjections;
			
			// project each shape 
			System.out.println("Projecting shapes.");
			
			kPCA.projectTrainingSets();
			
			ArrayList< double[] > scores = new ArrayList< double[] >();
			for(int j = 0; j < folders.size(); j++){
				scores.add(kPCA.scores.getCol(j).copyAsDoubleArray());
			}
			String scoresFilename = HEART_MODEL_BASE + "\\" + "phase_" + Integer.valueOf(i) + ".ccs";
			writeScores(scoresFilename, folders, scores);
			
			System.out.println("______________________________________");
			System.out.println("Finished work on phase: " + i);
		}
		
		System.out.println("\n\n");
		System.out.println("Parameter PCA starting.");
		int totPC = 0;
		for(int i = 0; i < numPhases; i++){
			totPC += principalComponents[i];
		}
		SimpleMatrix scores = new SimpleMatrix(totPC, folders.size());
		int offs = 0;
		for(int i = 0; i < numPhases; i++){
			offs += (i == 0) ? 0 : principalComponents[i-1];
			String cnfgFile = HEART_MODEL_BASE + "\\" + "phase_" + Integer.valueOf(i) + ".ccs";
			scores.setSubMatrixValue(offs, 0, parseScores(cnfgFile));			
		}
		PCA scorePCA = new PCA(scores, 1);
		scorePCA.variationThreshold = variationTh;
		//scorePCA.setKernel( new GaussianKernel(Math.sqrt(2)*2));
		//scorePCA.numProjections = 6;
		scorePCA.run();
		//scorePCA.projectTrainingSets();
		//SimpleMatrix s2 = scorePCA.scores;
		
		ArrayList< double[] > proj = new ArrayList< double[] >();
		for(int i = 0; i < folders.size(); i++){
			//proj.add(s2.getCol(i).copyAsDoubleArray());
			proj.add(scorePCA.projectTrainingShape(i));
		}
		
		System.out.println("Done.");
		return proj;
	}	
	
	/**
	 * Reads the scores for each data set from a .ccs file.
	 * @param filename
	 * @return
	 */
	private static SimpleMatrix parseScores(String filename){
		try {
			FileReader fr = new FileReader(filename);
			BufferedReader br = new BufferedReader(fr);
			
			String line = br.readLine();
			StringTokenizer tok = new StringTokenizer(line);
			tok.nextToken(); // skip "NUM_SAMPLES:"
			int numSamples = Integer.parseInt(tok.nextToken());
			line = br.readLine();
			tok = new StringTokenizer(line);
			tok.nextToken(); // skip "NUM_PRINCIPAL_COMPONENTS:"
			int numPC = Integer.parseInt(tok.nextToken());
			SimpleMatrix m = new SimpleMatrix(numPC, numSamples);
			for(int i = 0; i < numSamples; i++){
				line = br.readLine();
				tok = new StringTokenizer(line);
				tok.nextToken(); // skip "<STUDY_NAME>:"
				for(int j = 0; j < numPC; j++){
					m.setElementValue(j, i, Double.parseDouble(tok.nextToken()));
				}
			}
			
			
			br.close();
			fr.close();
			return m;
			
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return new SimpleMatrix();
	}
	
	
	private static void writeARFF(ArrayList<String> folders, ArrayList<double[]> scores ){
		System.out.println("\n\n");
		System.out.println("Writing file.");
		
		// output folder will be named: CardiacModel
		String outFolder = HEART_MODEL_BASE + "\\";
		new File(outFolder).mkdirs();
							
		
		String att = "@ATTRIBUTE ";
		
		try {
			PrintWriter writer = new PrintWriter(outFolder + "DilatedCardiomyopathy.arff","UTF-8");
			writer.println("@RELATION " + "DCM");
			writer.println();
			
			//writer.println(att + "GENDER"+ "\t" +"{M,F}");
			//writer.println(att + "AGE"+ "\t" +"NUMERIC");
			for(int i = 0; i < scores.get(0).length; i++){
				String line = att + "PC-" + Integer.valueOf(i) + "\t" + "NUMERIC";
				writer.println(line);
			}
			// NAD nothing abnormal detected, HT hypertrophic cardiomyopathy, DC dilated cardiomyopathy
			writer.println(att + "DILATED"+ "\t" +"{NAD,DC}");
			writer.println("@DATA");
			
			for(int i = 0; i < folders.size(); i++){
				String infoFile = folders.get(i).substring(0, folders.get(0).lastIndexOf("\\")+1) + "info.txt";
				ParseInfoFile info = new ParseInfoFile(infoFile);
				String line = "";//info.gender +"," + info.age;
				for(int k = 0; k < scores.get(0).length; k++){
					if(k==0){
						line += scores.get(i)[k];
					}else{
						line += "," + scores.get(i)[k];
					}
				}
				line += "," + info.attribute;
				writer.println(line);
			}			
			writer.close();			
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		
		
		System.out.println("Done.");
	}
	
	/**
	 * Writes the scores to file.
	 * @param filename
	 * @param names
	 * @param scores
	 */
	private static void writeScores(String filename, ArrayList<String> names, ArrayList<double[]> scores){
		try {
			PrintWriter writer = new PrintWriter(filename,"UTF-8");
			writer.println("NUM_SAMPLES: " + names.size());
			writer.println("NUM_PRINCIPAL_COMPONENTS: " + scores.get(0).length);
			
			for(int i = 0; i < names.size(); i++){
				String line = names.get(i);
				for( int j = 0; j < scores.get(0).length; j++){
					line += " " + Double.valueOf(scores.get(i)[j]);
				}
				writer.println(line);
			}
			writer.close();			
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Searches all sub-directories for the keyword ANALYSIS_KEY. This keyword indicates, that registration has been performed and meshes exist and 
	 * are assumed to be stored in this folder.
	 * @return An ArrayList containing the folders.
	 */
	private static ArrayList<String> getValidFolders(){
		String[] fl = DATA_PATH.list();
		ArrayList<String> validFolders = new ArrayList<String>();
		
		for(int i = 0; i < fl.length; i++){
			File fi = new File(DATA_PATH + "\\" + fl[i]);
			if(fi.isDirectory()){
				String[] list = fi.list();
				for(int j = 0; j < list.length; j++){
					if(list[j].contains(ANALYSIS_KEY)){
						validFolders.add(DATA_PATH + "\\" + fl[i] + "\\" + list[j]);
					}
				}
			}
		}
		return checkIfAllFoldersValid(validFolders);
	}
	
	/**
	 * This method checks if all directories in the ArrayList contain the necessary amount of phases.
	 * @param f The ArrayList of directories to be checked.
	 * @return An ArrayList containing only the valid directories.
	 */
	private static ArrayList<String> checkIfAllFoldersValid(ArrayList<String> f){
		ArrayList<String> fl = new ArrayList<String>();
		for(int i = 0; i < f.size(); i++){
			File file = new File(f.get(i));
			String[] list = file.list();
			int[] count = new int[numPhases];
			int cnt = 0;
			for(int j = 0; j < list.length; j++){
				File check = new File(f.get(i) + "\\" + list[j]);
				if(check.isDirectory() && check.getName().contains(PHASE_KEY)){
					int strPos = check.getName().indexOf(PHASE_KEY) + PHASE_KEY.length();
					int idx = Integer.valueOf(check.getName().substring(strPos));
					count[idx] = 1;
				}
			}
			for(int k = 0; k < numPhases; k++){
				cnt += count[k];
			}
			if(cnt == numPhases){
				fl.add(f.get(i));
			}else{
				System.out.println("Missing files in dataset: " + f.get(i));
			}
		}
		return fl;
	}
}
