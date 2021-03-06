#import <React/RCTAssert.h>
#import <React/RCTEventDispatcher.h>
#import <React/RCTConvert.h>
#import <React/RCTLog.h>

#import "TcpSockets.h"
#import "TcpSocketClient.h"
#import "PrinterBitmap.h"

// offset native ids by 5000
#define COUNTER_OFFSET 5000

@implementation TcpSockets
{
    NSMutableDictionary<NSNumber *,TcpSocketClient *> *_clients;
    int _counter;
}

RCT_EXPORT_MODULE()

- (NSArray<NSString *> *)supportedEvents
{
    return @[@"connect",
             @"connection",
             @"data",
             @"close",
             @"error"];
}

- (void)startObserving {
    // Does nothing
}

- (void)stopObserving {
    // Does nothing
}

-(void)dealloc
{
    for (NSNumber *cId in _clients.allKeys) {
        [self destroyClient:cId];
    }
}

- (TcpSocketClient *)createSocket:(nonnull NSNumber*)cId
{
    if (!cId) {
        RCTLogWarn(@"%@.createSocket called with nil id parameter.", [self class]);
        return nil;
    }

    if (!_clients) {
        _clients = [NSMutableDictionary new];
    }

    if (_clients[cId]) {
        RCTLogWarn(@"%@.createSocket called twice with the same id.", [self class]);
        return nil;
    }

    _clients[cId] = [TcpSocketClient socketClientWithId:cId andConfig:self];

    return _clients[cId];
}

RCT_EXPORT_METHOD(connect:(nonnull NSNumber*)cId
                  host:(NSString *)host
                  port:(int)port
                  withOptions:(NSDictionary *)options)
{
    TcpSocketClient *client = _clients[cId];
    if (!client) {
      client = [self createSocket:cId];
    }

    NSError *error = nil;
    if (![client connect:host port:port withOptions:options error:&error])
    {
        [self onError:client withError:error];
        return;
    }
}

RCT_EXPORT_METHOD(write:(nonnull NSNumber*)cId
                  string:(NSString *)base64String
                  callback:(RCTResponseSenderBlock)callback) {
    TcpSocketClient* client = [self findClient:cId];
    if (!client) return;

    // iOS7+
    // TODO: use https://github.com/nicklockwood/Base64 for compatibility with earlier iOS versions
    NSData *data = [[NSData alloc] initWithBase64EncodedString:base64String options:0];
    [client writeData:data callback:callback];
}

RCT_EXPORT_METHOD(writeImage:(nonnull NSNumber*)cId
                  string:(NSString *)base64String
                  withOptions:(NSDictionary *) options
                  callback:(RCTResponseSenderBlock)callback) {
    TcpSocketClient* client = [self findClient:cId];
    if (!client) return;
    
    @try{
        NSInteger nWidth = [[options valueForKey:@"width"] integerValue];
        if(!nWidth) nWidth = 490;
        NSInteger lWidth = [[options valueForKey:@"label_width"] integerValue];
        if(!lWidth) lWidth = 70;
        NSInteger lHeight = [[options valueForKey:@"label_height"] integerValue];
        if(!lHeight) lHeight = 100;
        NSData *imageData = [[NSData alloc] initWithBase64EncodedString:base64String options:NSDataBase64DecodingIgnoreUnknownCharacters];
        UIImage *uiImage = [[UIImage alloc] initWithData:imageData];
        CGFloat imgWidth = uiImage.size.width;
        CGFloat imgHeigth = uiImage.size.height;
        NSInteger width = (nWidth + 7) / 8 * 8;
        NSInteger height = imgHeigth * width / imgWidth;
        PrinterBitmap *bitmap = [[PrinterBitmap alloc] initWithUIImage:uiImage maxWidth:(int)width];
        NSData *printData = [bitmap getDataForPrint];

        width /= 8;
        
        NSMutableData *command = [[NSMutableData alloc] init];
        NSString *str = [NSString stringWithFormat:@ "SIZE %ld mm, %ld mm\r\nDIRECTION 1\r\nCLS\r\nBITMAP 0,0,%ld,%ld,0,", lWidth, lHeight, width, height];
        [command appendData:[str dataUsingEncoding:CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000)]];
        [command appendData:printData];
        [command appendData:[@"\r\nPRINT 1,1\r\n" dataUsingEncoding:CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000)]];
        [client writeData:command callback:callback];
    }
    @catch(NSException *e){
        NSLog(@"ERROR IN PRINTING IMG: %@",[e callStackSymbols]);
    }
}

RCT_EXPORT_METHOD(end:(nonnull NSNumber*)cId) {
    [self endClient:cId];
}

RCT_EXPORT_METHOD(destroy:(nonnull NSNumber*)cId) {
    [self destroyClient:cId];
}

RCT_EXPORT_METHOD(listen:(nonnull NSNumber*)cId
                  withOptions:(nonnull NSDictionary *)options)
{
    TcpSocketClient* client = _clients[cId];
    if (!client) {
      client = [self createSocket:cId];
    }

    NSError *error = nil;
    if (![client listen:options error:&error])
    {
        [self onError:client withError:error];
        return;
    }
}

RCT_EXPORT_METHOD(setNoDelay:(nonnull NSNumber*)cId noDelay:(BOOL)noDelay) {
    TcpSocketClient* client = [self findClient:cId];
    if (!client) return;
    
    [client setNoDelay:noDelay];
}

RCT_EXPORT_METHOD(setKeepAlive:(nonnull NSNumber*)cId enable:(BOOL)enable initialDelay:(int)initialDelay) {
    TcpSocketClient* client = [self findClient:cId];
    if (!client) return;
    
    [client setKeepAlive:enable initialDelay:initialDelay];
}

- (void)onConnect:(TcpSocketClient*) client
{
    [self sendEventWithName:@"connect"
                       body:@{ @"id": client.id, @"address" : [client getAddress] }];
}

-(void)onConnection:(TcpSocketClient *)client toClient:(NSNumber *)clientID {
    _clients[client.id] = client;

    [self sendEventWithName:@"connection"
                       body:@{ @"id": clientID, @"info": @{ @"id": client.id, @"address" : [client getAddress] } }];
}

- (void)onData:(NSNumber *)clientID data:(NSData *)data
{
    NSString *base64String = [data base64EncodedStringWithOptions:0];
    [self sendEventWithName:@"data"
                       body:@{ @"id": clientID, @"data" : base64String }];
}

- (void)onClose:(NSNumber*) clientID withError:(NSError *)err
{
    TcpSocketClient* client = [self findClient:clientID];
    if (!client) {
        RCTLogWarn(@"onClose: unrecognized client id %@", clientID);
    }

    if (err) {
        [self onError:client withError:err];
    }

    [self sendEventWithName:@"close"
                       body:@{ @"id": clientID, @"hadError": err == nil ? @NO : @YES }];

    [_clients removeObjectForKey:clientID];
}

- (void)onError:(TcpSocketClient*) client withError:(NSError *)err {
    NSString *msg = err.localizedFailureReason ?: err.localizedDescription;
    [self sendEventWithName:@"error"
                       body:@{ @"id": client.id, @"error": msg }];

}

-(TcpSocketClient*)findClient:(nonnull NSNumber*)cId
{
    TcpSocketClient *client = _clients[cId];
    if (!client) {
        NSString *msg = [NSString stringWithFormat:@"no client found with id %@", cId];
        [self sendEventWithName:@"error"
                           body:@{ @"id": cId, @"error": msg }];

        return nil;
    }

    return client;
}

-(void)endClient:(nonnull NSNumber*)cId
{
    TcpSocketClient* client = [self findClient:cId];
    if (!client) return;

    [client end];
}

-(void)destroyClient:(nonnull NSNumber*)cId
{
    TcpSocketClient* client = [self findClient:cId];
    if (!client) return;

    [client destroy];
}

-(NSNumber*)getNextId {
    return @(_counter++ + COUNTER_OFFSET);
}

@end
