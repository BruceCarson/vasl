/*
 * $Id: ASLMap.java 8530 2012-12-26 04:37:04Z uckelman $
 *
 * Copyright (c) 2013 by Brent Easton
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, copies are available
 * at http://www.opensource.org.
 */

package VASL.build.module;

import VASL.build.module.map.boardArchive.BoardArchive;
import VASL.LOS.counters.CounterMetadataFile;
import VASL.build.module.map.boardArchive.SharedBoardMetadata;
import VASL.build.module.map.boardPicker.ASLBoard;
import VASL.build.module.map.boardPicker.BoardException;
import VASL.build.module.map.boardPicker.VASLBoard;
import VASSAL.build.GameModule;
import VASSAL.build.module.Map;
import VASSAL.build.module.map.boardPicker.Board;
import VASSAL.configure.BooleanConfigurer;
import VASSAL.configure.ColorConfigurer;
import VASSAL.counters.GamePiece;
import VASSAL.counters.Properties;
import VASSAL.counters.Stack;
import VASSAL.tools.DataArchive;
import VASSAL.tools.ErrorDialog;
import VASSAL.tools.imageop.Op;
import org.apache.commons.io.IOUtils;
import org.jdom2.JDOMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;

import static VASSAL.build.GameModule.getGameModule;

public class ASLMap extends Map {

    // FredKors popup menu
    private JPopupMenu m_mnuMainPopup = null;

    // board metadata
    //TODO: the shared metadata is static so it doesn't have to be passed around. Is there a better way?
    private VASL.LOS.Map.Map VASLMap;
    private static final String sharedBoardMetadataFileName = "boardData/SharedBoardMetadata.xml"; // name of the shared board metadata file
    private static SharedBoardMetadata sharedBoardMetadata = null;
    private boolean legacyMode;                     // true if unable to create a VASL map or LOS data is missing

    // counter metadata
    private static CounterMetadataFile counterMetadata = null;

    // used to log errors in the VASSAL error log
    private static final Logger logger = LoggerFactory.getLogger(ASLMap.class);
    private ShowMapLevel m_showMapLevel = ShowMapLevel.ShowAll;
    // background color preference
    private static final String preferenceTabName = "VASL";

  public ASLMap() {


      super();

      try {
          readMetadata();
      } catch (JDOMException e) {

          // give up if there's any problem reading the shared metadata file
          ErrorDialog.bug(e);
      }
      m_mnuMainPopup = new JPopupMenu();

      // FredKors: creation of the toolbar button
      // that opens the popup menu
      JButton l_Menu = new JButton();

    try
    {
        l_Menu.setIcon(new ImageIcon(Op.load("QC/menu.png").getImage(null)));
    }
    catch (Exception ex) 
    {
        ex.printStackTrace();
    }
    
    l_Menu.setMargin(new Insets(0, 0, 0, 0));
    l_Menu.setAlignmentY(0.0F);
    
    l_Menu.addActionListener(new ActionListener() 
    {
        public void actionPerformed(ActionEvent evt) 
        {
            if (evt.getSource() instanceof JButton)
                m_mnuMainPopup.show((JButton)evt.getSource(), 0, 0);
        }
    });

    // add the first element to the popupp menu
    JMenuItem l_SelectItem = new JMenuItem("Select");
    l_SelectItem.setBackground(new Color(255,255,255));
    m_mnuMainPopup.add(l_SelectItem);
    m_mnuMainPopup.addSeparator();    

    // add the menu button to the toolbar
    getToolBar().add(l_Menu); 
    getToolBar().addSeparator();
    // background color preference
    final ColorConfigurer backgroundcolor = new ColorConfigurer("backcolor", "Set Color of space around Map (requires VASL restart)", Color.white);
    getGameModule().getPrefs().addOption(preferenceTabName, backgroundcolor);
}
  
  /*
   *  Work-around for VASL board being 1 pixel too large causing double stacks to form along board edges.
   *  Any snap to a board top or left edge half hex, bump it 1 pixel up or left on to the next board.
   *  
   *  */
  public Point snapTo(Point p) 
  { // FredKors 22/12/2013
    final Point l_pSnapTo = super.snapTo(p);
    Point l_pShiftedXY, l_pShiftedY, l_pShiftedX;
    
    l_pShiftedXY = new Point (l_pSnapTo);

    l_pShiftedXY.x -= 3;
    l_pShiftedXY.y -= 3; // I move the snap point 3 pixel up and left: if the map changes, the snapTo could return a different point, otherwise nothing changes
    l_pShiftedXY = super.snapTo(l_pShiftedXY);
    
    if (findBoard(l_pShiftedXY) != null) //  Return to the snapTo point if I moved off the top border or the left border
        return l_pShiftedXY;
    
    l_pShiftedY = new Point (l_pSnapTo);
    
    l_pShiftedY.y -= 3; // I move the snap point 3 pixel up: if the map changes, the snapTo could return a different point, otherwise nothing changes
    l_pShiftedY = super.snapTo(l_pShiftedY);
    
    if (findBoard(l_pShiftedY) == null) // I moved off the top border, return to the snapTo point
        l_pShiftedY.y = l_pSnapTo.y;
    
    l_pShiftedX = new Point (l_pShiftedY);
    
    l_pShiftedX.x -= 3;// I move the snap point 3 pixel left: if the map changes, the snapTo could return a different point, otherwise nothing changes
    l_pShiftedX = super.snapTo(l_pShiftedX);
    
    if (findBoard(l_pShiftedX) == null) // I moved off the left border
        return l_pShiftedY;
    
    return l_pShiftedX;        
    
  }
  // return the popup menu
  public JPopupMenu getPopupMenu()
  {
      return m_mnuMainPopup;
  }

    @Override
    public synchronized void setBoards(Collection<Board> c) {

        for (Board boardc: c) {
            VASLBoard testboardexists = (VASLBoard) boardc;
            if (testboardexists.getVASLBoardArchive() == null) {
                GameModule.getGameModule().getChatter().send("Board missing. Auto-synching of boards requires board directory in board picker matches the board directory set in preferences. Close this game and start new game");
                return;
            }
        }
        super.setBoards(c);
        String info = "Using board(s): ";
        for (Board board : boards) {
            ASLBoard b = (ASLBoard) board;
            info += b.getName() + "(v" + b.getVersion() + ") ";
        }
        GameModule.getGameModule().warn(info);
        buildVASLMap();


    }

    /**
     * read the shared board metadata
     */
    private void readMetadata() throws JDOMException {

        DataArchive archive = GameModule.getGameModule().getDataArchive();
        // shared board metadata
        try (InputStream inputStream =  archive.getInputStream(sharedBoardMetadataFileName)) {
            sharedBoardMetadata = new SharedBoardMetadata();
            sharedBoardMetadata.parseSharedBoardMetadataFile(inputStream);

        // give up on any errors
        } catch (IOException e) {
            sharedBoardMetadata = null;
            throw new JDOMException("Cannot read the shared metadata file", e);
        } catch (JDOMException e) {
            sharedBoardMetadata = null;
            throw new JDOMException("Cannot read the shared metadata file", e);
        } catch (NullPointerException e) {
            sharedBoardMetadata = null;
            throw new JDOMException("Cannot read the shared metadata file", e);
        }
    }

    /**
     * Builds the VASL map
     */
    protected void buildVASLMap() {
        // set background color from preference
        super.bgColor = (Color) getGameModule().getPrefs().getValue("backcolor");
        repaint();
        legacyMode = false;
        boolean nullBoards = false; // are null boards being used?

        // create an empty VASL map of the correct size
        LinkedList<VASLBoard> VASLBoards = new LinkedList<VASLBoard>(); // keep the boards so they are instantiated once
        try {

            // see if there are any legacy boards in the board set
            // and determine the size of the map
            final Rectangle mapBoundary = new Rectangle(0,0);
            double hexHeight = 0.0;
            double hexWidth = 0.0;
            for(Board b: boards) {

                final VASLBoard board = (VASLBoard) b;

                // ignore null boards
                if(!"NUL".equals(b.getName()) && !"NULV".equals(b.getName())){

                    if(board.isLegacyBoard()) {
                        throw new BoardException("VASL LOS disabled - Board " + board.getName() + " does not support LOS checking. VASSAL los active");
                    }

                    mapBoundary.add(b.bounds());
                    VASLBoards.add(board);

                    // make sure the hex geometry of all boards is the same
                    if (hexHeight != 0.0 && Math.round(board.getHexHeight()) != Math.round(hexHeight) || hexWidth != 0.0 && Math.round(board.getHexWidth()) != Math.round(hexWidth)) {
                        throw new BoardException("VASL LOS disabled: Map configuration contains multiple hex sizes. VASSAL los active");
                    }
                    hexHeight = board.getHexHeight();
                    hexWidth = board.getHexWidth();
                }
                else {
                    nullBoards = true;
                }
            }

            // remove the edge buffer from the map boundary size
            mapBoundary.width -= edgeBuffer.width;
            mapBoundary.height -= edgeBuffer.height;

            // create the VASL map
            //DR added new variables to pass cropping values
            String passgridconfig="Normal";
            boolean isCropping=false;
            int fullhexadj=0;

            VASLBoard b = VASLBoards.get(0); // we can use the geometry of any board - assuming all are the same
            if (b.getVASLBoardArchive().getHexGridConfig() != null) {passgridconfig = b.getVASLBoardArchive().getHexGridConfig();}
            if (b.isCropped()) { isCropping=true;}
            if (b.nearestFullRow) {
                passgridconfig = "FullHex";
                fullhexadj=-1;
                if (b.getCropBounds().getX() == 0) {passgridconfig = "FullHexLeftHalf"; fullhexadj=0;}
                if (b.getCropBounds().getMaxX() == b.getUncroppedSize().getWidth()) {passgridconfig = "FullHexRightHalf"; fullhexadj=0;}
            }
            //if (!(b.getA1CenterX()==0) && !(b.getA1CenterX()==-999)) { passgridconfig= passgridconfig +"Offset";}
            double passA1CenterY = b.getA1CenterY();
            if (!(b.getA1CenterX()==0) && !(b.getA1CenterX()==-999) && !(b.getA1CenterX()==-901)) {
                if (b.getCropBounds().getX() != 0) {
                    passgridconfig = passgridconfig + "Offset";  // only need to set this if cropping the left edge when board has offset (ie RB and RO)
                }
                //if(b.getA1CenterY()==65 && b.getCropBounds().getX()==0){passA1CenterY =32.25;}
            }
            VASLMap = new VASL.LOS.Map.Map(
                    hexWidth,
                    hexHeight,
                    (int) Math.round(mapBoundary.width/ b.getHexWidth()) + 1 + fullhexadj,
                    (int) Math.round(mapBoundary.height/ b.getHexHeight()),
                    b.getA1CenterX(),
                    passA1CenterY,
                    mapBoundary.width,
                    mapBoundary.height,
                    sharedBoardMetadata.getTerrainTypes(), passgridconfig, isCropping);
        }

        // clean up and fall back to legacy mode if an unexpected exception is thrown
        catch (BoardException e) {

            setLegacyMode();
            logError(e.toString());
            GameModule.getGameModule().getChatter().send(e.toString());
        }
        catch (Exception e) {

            setLegacyMode();
            VASLBoards = null;
            logError("LOS disabled - unexpected error");
            logException(e);
            GameModule.getGameModule().getChatter().send("LOS disabled - unexpected error: " + e.toString());
        }

        // add the boards to the VASL map
        try {

            // load the LOS data
            if(!legacyMode) {

                for (VASLBoard board : VASLBoards) {

                    // read the LOS data and flip/crop the board if needed
                    // DR added variable to support cropping and flipping
                    String croptype="Normal"; boolean isCropping=false; double Fullhexadj= 0;
                    double gridadj=0;

                    if (board.nearestFullRow) {
                        croptype="FullHex";
                        Fullhexadj= board.getHexWidth()/2;
                        if (board.getCropBounds().getX()==0) {croptype="FullHexLeftHalf";}
                        if (board.getCropBounds().getMaxX()== board.getUncroppedSize().getWidth()) {croptype="FullHexRightHalf";}
                    }
                    if(board.isCropped()) {
                        isCropping=true;
                        if (!croptype.contains("LeftHalf")) {
                            if (!(board.getA1CenterX() == -901)) {
                                gridadj = board.getA1CenterX() - Fullhexadj;
                                if (board.getCropBounds().width== -1) {gridadj=0;}
                            } else {
                                gridadj = -Fullhexadj;
                            }
                        }

                    }
                    if (!(board.getA1CenterX()==0) && !(board.getA1CenterX()==-999) && !(board.getA1CenterX()==-901)) {
                        if (board.getCropBounds().getX() != 0) {
                            croptype = croptype + "Offset";  // only need to set this if cropping the left edge when board has offset (ie RB and RO)
                        }
                    }
                    VASL.LOS.Map.Map LOSData = board.getLOSData(sharedBoardMetadata.getTerrainTypes(), croptype, isCropping, gridadj);

                    // apply the SSR changes, crop and flip if needed
                    board.applyColorSSRules(LOSData, sharedBoardMetadata.getLOSSSRules(), gridadj);

                    if(board.isCropped()) {
                        LOSData = board.cropLOSData(LOSData);
                    }

                    if(board.isReversed()){
                        LOSData.flip();
                    }

                    // add the board LOS data to the map
                    // .insertMap is designed to work with only geo board thus need to test for non-geo boards (in this situation geo boards inclues AP boards and deluxe boards)
                    // DR added code to handle maps of one board such as HASL maps
                    if ((board.getWidth()==33 && board.getHeight()==10) || (board.getWidth()==17 && board.getHeight()==20) || (board.getWidth() == 15 && board.getHeight() ==5)) {
                        //line below is not a good fix; make sure it works in all situations or change
                            int cropadj=1;  // ensures that cropping a board by row works properly DR (rows such as A7 have uneven total height which results in incorrect choice from gridToHex)
                            if (!VASLMap.insertMap(
                                    LOSData,
                                    VASLMap.gridToHex(board.getBoardLocation().x, board.getBoardLocation().y + cropadj + (nullBoards ? 1 : 0)))) {

                                // didn't work, so assume an unsupported feature
                                throw new BoardException("VASL LOS Disabled: Unable to insert board " + board.getName() + " into the VASL map. VASSAL los active");
                            }
                    }
                    else {
                            // add board LOS data for non-standard size board
                        //line below is not a good fix; make sure it works in all situations or change
                        int cropadj=1;  // ensures that cropping a board by row works properly DR (rows such as A7 have uneven total height which results in incorrect choice from gridToHex)
                        if (VASLBoards.size()==1){
                            VASLMap.insertOneMap(LOSData);
                        } else {
                            if (!VASLMap.insertNonGeoMap(
                                    LOSData,
                                    VASLMap.gridToHex(board.getBoardLocation().x, board.getBoardLocation().y + cropadj + (nullBoards ? 1 : 0)))) {

                                // didn't work, so assume an unsupported feature
                                throw new BoardException("VASL LOS Disabled: Unable to insert board " + board.getName() + " into the VASL map. VASSAL los active");
                            }
                        }
                    }
                }
                GameModule.getGameModule().warn("VASL LOS Enabled");
            }
        }
        catch (BoardException e) {

            setLegacyMode();
            logError(e.toString());
            GameModule.getGameModule().getChatter().send("VASL LOS Disabled: " + e.toString() + ". VASSAL los active");
        }
        catch (Exception e) {

            setLegacyMode();
            logError("LOS disabled - unexpected error");
            logException(e);
            GameModule.getGameModule().getChatter().send("VASL LOS disabled: " + e.toString() + ". VASSAL los active");
        }
        finally {
            // free up memory
            VASLBoards = null;
        }
    }

    /**
     * Sets status of LOS engine to legacy mode
     */
    private void setLegacyMode() {
        legacyMode = true;
        VASLMap = null;

    }
    /**
     * @return the VASL map
     */
    public VASL.LOS.Map.Map getVASLMap(){
        return VASLMap;
    }

    /**
     * @return the shared board metadata
     */
    public static SharedBoardMetadata getSharedBoardMetadata() {
        return sharedBoardMetadata;
    }

    /**
     * @return the counter metadata
     */
    public static CounterMetadataFile getCounterMetadata() {
        return counterMetadata;
    }

    /**
     * @return true if the map is in legacy mode (i.e. pre-6.0)
     */
    public boolean isLegacyMode(){
        return legacyMode;
    }

    /**
     * Log a string to the VASSAL error log
     * @param error the error string
     */
    private void logError(String error) {
        logger.info(error);
    }

    /**
     * Log an exception to the VASSAL error log
     * @param error the exception
     */
    private void logException(Throwable error) {
        logger.info("", error);
    }

    public BufferedImage getImgMapIcon(Point pt, double size, double os_scale) {
        BufferedImage img = new BufferedImage((int)size, (int)size, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g2d = img.createGraphics();

        double dzoom = 0.0;
        Rectangle rect = null;

        for (Board b: getBoards()) {
            if (rect == null) {
                final double mag = b.getMagnification();
                dzoom = os_scale / mag;

                rect = new Rectangle(
                  (int)((pt.x * os_scale - size/2) / mag),
                  (int)((pt.y * os_scale - size/2) / mag),
                  (int)(size / mag),
                  (int)(size / mag)
                );

                g2d.translate(-rect.x, -rect.y);
            }

            b.drawRegion(g2d, getLocation(b, dzoom), rect, dzoom, null);
        }

        drawPiecesNonStackableInRegion(g2d, rect, dzoom);

        g2d.dispose();
        return img;
    }

  protected void drawPiecesNonStackableInRegion(Graphics g, Rectangle visibleRect, double dZoom)
  {
      Graphics2D g2d = (Graphics2D) g;
      Composite oldComposite = g2d.getComposite();
      g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pieceOpacity));
      
      GamePiece[] stack = pieces.getPieces();
      
      for (int i = 0; i < stack.length; ++i) 
      {
        Point pt = stack[i].getPosition();
        
        if (stack[i].getClass() != Stack.class) 
        {
          if (Boolean.TRUE.equals(stack[i].getProperty(Properties.NO_STACK))) 
              stack[i].draw(g, (int)(pt.x * dZoom), (int)(pt.y * dZoom), null, dZoom);
        }
      }
      
      g2d.setComposite(oldComposite);
  }    
  
  public void setShowMapLevel(ShowMapLevel showMapLevel) {
      m_showMapLevel = showMapLevel;
  }
    
    @Override
  public boolean isPiecesVisible() {
    return pieceOpacity != 0;
  }   
  
    @Override
    public void drawPiecesInRegion(Graphics g,
                                 Rectangle visibleRect,
                                 Component c) {

    Graphics2D g2d = (Graphics2D) g;
    Composite oldComposite = g2d.getComposite();
    g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pieceOpacity));

    final double os_scale = g2d.getDeviceConfiguration().getDefaultTransform().getScaleX();
    final double dzoom = getZoom() * os_scale;

    GamePiece[] stack = pieces.getPieces();

    for (int i = 0; i < stack.length; ++i)
    {
        Point pt = mapToDrawing(stack[i].getPosition(), os_scale);

        if (stack[i].getClass() == Stack.class)
        {
            if (m_showMapLevel == ShowMapLevel.ShowAll)
                getStackMetrics().draw((Stack) stack[i], pt, g, this, dzoom, visibleRect);
        }
        else
        {
            if (m_showMapLevel == ShowMapLevel.ShowAll  || (stack[i].getProperty("overlay") != null && m_showMapLevel == ShowMapLevel.ShowMapOnly)) // always show overlays
            {
                stack[i].draw(g, pt.x, pt.y, c, dzoom);

                if (Boolean.TRUE.equals(stack[i].getProperty(Properties.SELECTED)))
                    highlighter.draw(stack[i], g, pt.x, pt.y, c, dzoom);
            }
            else if (m_showMapLevel == ShowMapLevel.ShowMapAndOverlay)
            {
                if (Boolean.TRUE.equals(stack[i].getProperty(Properties.NO_STACK)))
                {
                    stack[i].draw(g, pt.x, pt.y, c, dzoom);

                    if (Boolean.TRUE.equals(stack[i].getProperty(Properties.SELECTED)))
                        highlighter.draw(stack[i], g, pt.x, pt.y, c, dzoom);
                }
            }
        }
/*
        // draw bounding box for debugging
        final Rectangle bb = stack[i].boundingBox();
        g.drawRect(pt.x + bb.x, pt.y + bb.y, bb.width, bb.height);
*/
        }
        
        g2d.setComposite(oldComposite); 
    }

    @Override
  public void drawPieces(Graphics g, int xOffset, int yOffset) 
  {

    Graphics2D g2d = (Graphics2D) g;
    Composite oldComposite = g2d.getComposite();
    g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pieceOpacity));

    final double os_scale = g2d.getDeviceConfiguration().getDefaultTransform().getScaleX();
    final double dzoom = getZoom() * os_scale;

    GamePiece[] stack = pieces.getPieces();

    for (int i = 0; i < stack.length; ++i)
    {
        if (m_showMapLevel == ShowMapLevel.ShowAll || (stack[i].getProperty("overlay") != null && m_showMapLevel == ShowMapLevel.ShowMapOnly)) // always show overlays
        {
            Point pt = mapToDrawing(stack[i].getPosition(), os_scale);

            stack[i].draw(g, pt.x + xOffset, pt.y + yOffset, theMap, dzoom);

            if (Boolean.TRUE.equals(stack[i].getProperty(Properties.SELECTED)))
                highlighter.draw(stack[i], g, pt.x - xOffset, pt.y - yOffset, theMap, dzoom);
        }
        else if (m_showMapLevel == ShowMapLevel.ShowMapAndOverlay)
        {
            if (stack[i].getClass() != Stack.class)
            {
                if (Boolean.TRUE.equals(stack[i].getProperty(Properties.NO_STACK)))
                {
                    Point pt = mapToDrawing(stack[i].getPosition(), os_scale);

                    stack[i].draw(g, pt.x + xOffset, pt.y + yOffset, theMap, dzoom);

                    if (Boolean.TRUE.equals(stack[i].getProperty(Properties.SELECTED)))
                        highlighter.draw(stack[i], g, pt.x - xOffset, pt.y - yOffset, theMap, dzoom);
                }
            }
        }
    }

    g2d.setComposite(oldComposite);
  }

  
  public enum ShowMapLevel
  {
      ShowAll,
      ShowMapAndOverlay,
      ShowMapOnly        
  }
}
