"""Generated class for metadata.json"""
from .model_cloud import CloudModel
from .model_system import SystemModel
from .model_gateway import GatewayModel
from .model_discovery import DiscoveryModel
from .model_localnet import LocalnetModel
from .model_testing import TestingModel
from .model_features import TestingModel
from .model_pointset import PointsetModel


class Metadata:
  """Generated schema class"""

  def __init__(self):
    self.timestamp = None
    self.version = None
    self.upgraded_from = None
    self.description = None
    self.hash = None
    self.device_version = None
    self.cloud = None
    self.system = None
    self.gateway = None
    self.discovery = None
    self.localnet = None
    self.testing = None
    self.features = None
    self.pointset = None

  @staticmethod
  def from_dict(source):
    if not source:
      return None
    result = Metadata()
    result.timestamp = source.get('timestamp')
    result.version = source.get('version')
    result.upgraded_from = source.get('upgraded_from')
    result.description = source.get('description')
    result.hash = source.get('hash')
    result.device_version = source.get('device_version')
    result.cloud = CloudModel.from_dict(source.get('cloud'))
    result.system = SystemModel.from_dict(source.get('system'))
    result.gateway = GatewayModel.from_dict(source.get('gateway'))
    result.discovery = DiscoveryModel.from_dict(source.get('discovery'))
    result.localnet = LocalnetModel.from_dict(source.get('localnet'))
    result.testing = TestingModel.from_dict(source.get('testing'))
    result.features = FeatureEnumeration.map_from(source.get('features'))
    result.pointset = PointsetModel.from_dict(source.get('pointset'))
    return result

  @staticmethod
  def map_from(source):
    if not source:
      return None
    result = {}
    for key in source:
      result[key] = Metadata.from_dict(source[key])
    return result

  @staticmethod
  def expand_dict(input):
    result = {}
    for property in input:
      result[property] = input[property].to_dict() if input[property] else {}
    return result

  def to_dict(self):
    result = {}
    if self.timestamp:
      result['timestamp'] = self.timestamp # 5
    if self.version:
      result['version'] = self.version # 5
    if self.upgraded_from:
      result['upgraded_from'] = self.upgraded_from # 5
    if self.description:
      result['description'] = self.description # 5
    if self.hash:
      result['hash'] = self.hash # 5
    if self.device_version:
      result['device_version'] = self.device_version # 5
    if self.cloud:
      result['cloud'] = self.cloud.to_dict() # 4
    if self.system:
      result['system'] = self.system.to_dict() # 4
    if self.gateway:
      result['gateway'] = self.gateway.to_dict() # 4
    if self.discovery:
      result['discovery'] = self.discovery.to_dict() # 4
    if self.localnet:
      result['localnet'] = self.localnet.to_dict() # 4
    if self.testing:
      result['testing'] = self.testing.to_dict() # 4
    if self.features:
      result['features'] = FeatureEnumeration.expand_dict(self.features) # 2
    if self.pointset:
      result['pointset'] = self.pointset.to_dict() # 4
    return result
