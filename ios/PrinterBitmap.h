//
//  PrinterBitmap.h
//  TcpSockets
//
//  Created by Tung Mai on 8/16/20.
//  Copyright © 2020 Peel, Inc. All rights reserved.
//

#import <Foundation/Foundation.h>

typedef struct ARGBPixel
{
    unsigned char alpha;
    unsigned char red;
    unsigned char green;
    unsigned char blue;
} ARGBPixel;

@interface BitmapImage : NSObject
@property(nonatomic,strong)NSData* bitmap;
@property(nonatomic,assign)NSInteger width;
@property(nonatomic,assign)NSInteger height;
@end

@interface PrinterBitmap : NSObject
{
    UIImage *m_image;
    NSData *imageData;
}

- (id)initWithUIImage:(UIImage *)image maxWidth:(int)maxWidth;
-(NSData*)getDataForPrint;

@end
